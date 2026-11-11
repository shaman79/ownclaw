package com.ownclaw.conversation;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Set;

/**
 * The copy of a pasted image that the cloud model is sent: made once, when the image is uploaded,
 * and stored beside it ({@link FileStorageService#store}), so that every request of every later
 * turn sends the same bytes. The file as it was pasted is never touched: skills are handed that.
 * <p>
 * Only an image this code has decoded is one: a PNG, a JPEG or a GIF by its first bytes, read whole
 * by the JDK's reader for that format and no other -- a reader picked by ImageIO's own guess takes a
 * text file for a WBMP. Anything else stays private: a WebP among them, which the JDK cannot decode,
 * so this code could neither check that its bytes are a picture nor fit a large one to the model.
 * Browsers put a pasted image on the clipboard as a PNG.
 * <p>
 * The copy is the image at the resolution the model reads it at, by Anthropic's own rule for its
 * high-resolution tier (Claude 4.7 and later, claude-opus-5-5 among them): the largest size, aspect
 * kept, whose edges padded to 28 px are at most {@link #MAX_EDGE} and whose visual tokens are at
 * most {@link #MAX_TOKENS} ({@link #resizedSize}, the reference implementation of
 * platform.claude.com/docs/en/build-with-claude/vision-coordinates). The API itself scales a larger
 * image down to exactly that, so the model is shown nothing less than it would read; a smaller one
 * is not scaled at all. Sized here, it stays within every limit of the API too: 8,000 px, 3,000 px
 * in a request of more than 20 images, and 10 MB base64 ({@link #MAX_BASE64}).
 * <p>
 * The original's bytes are the copy when they are a PNG or a JPEG within the tier and that limit and
 * hold nothing but the picture. Otherwise the copy is encoded from the decoded pixels -- a PNG, or,
 * when a PNG would be larger than one image may be, a JPEG of high quality: so what leaves carries
 * no text chunk, EXIF or XMP block, comment, colour profile, thumbnail or bytes after the image's
 * end, which a picture can hold and the owner never sees. A GIF is always encoded again: the model
 * reads only its first frame, which is what its copy is.
 */
public final class CloudImageCopy {

    static {
        // Spring Boot runs headless; so does this when it runs before Spring does -- in a test. No
        // image here is ever shown, and on a server with no display AWT must not look for one.
        if (System.getProperty("java.awt.headless") == null) System.setProperty("java.awt.headless", "true");
    }

    private CloudImageCopy() {}

    /** The longest edge the high-resolution tier reads, in pixels. */
    public static final int MAX_EDGE = 2576;

    /** The visual tokens the high-resolution tier reads an image as, at most. */
    public static final int MAX_TOKENS = 4784;

    /**
     * The largest image the Messages API takes, base64-encoded: 10 MB, read as 10,000,000 bytes --
     * the smaller of the two ways to read it (com.ownclaw.llm.LlmMessage#IMAGE_BASE64_LIMIT).
     */
    public static final long MAX_BASE64 = 10_000_000L;

    /**
     * The qualities a JPEG copy is tried at, best first: the first whose copy fits {@link #MAX_BASE64}.
     * A picture of at most 4,784 visual tokens -- 3.75 megapixels -- is about a megabyte at the
     * first; the others are for a picture of noise, so that even that one can be sent.
     */
    static final float[] JPEG_QUALITIES = {0.92f, 0.85f, 0.75f, 0.6f};

    enum Format {
        PNG("png", "image/png"), JPEG("jpeg", "image/jpeg"), GIF("gif", "image/gif");

        final String reader;
        final String mediaType;

        Format(String reader, String mediaType) {
            this.reader = reader;
            this.mediaType = mediaType;
        }
    }

    /**
     * The copy.
     *
     * @param mediaType      image/png, image/jpeg or image/gif: what its bytes are
     * @param width          its width in pixels: what the model reads
     * @param height         its height in pixels
     * @param originalWidth  the pasted image's width, which skills are handed
     * @param originalHeight the pasted image's height
     * @param bytes          its bytes
     * @param original       whether they are the pasted file's own bytes, which then need no copy beside it
     */
    public record Copy(String mediaType, int width, int height, int originalWidth, int originalHeight,
                       byte[] bytes, boolean original) {

        /** The hex SHA-256 of its bytes. */
        public String sha256() {
            return CloudImageCopy.sha256(bytes);
        }
    }

    /** A copy, or why the file stays private: exactly one of the two. */
    public record Made(Copy copy, String privateBecause) {
        static Made of(Copy copy) {
            return new Made(copy, null);
        }

        static Made stays(String because) {
            return new Made(null, because);
        }
    }

    /** A pixel size. */
    record Size(int width, int height) {}

    /** The copy of the image in {@code file}, or why it stays private. */
    public static Made of(Path file) throws IOException {
        byte[] head = new byte[16];
        int got;
        try (InputStream in = Files.newInputStream(file)) {
            got = in.readNBytes(head, 0, head.length);
        }
        Format format = sniff(head, got);
        if (format == null) {
            return Made.stays(webp(head, got)
                    ? "it is a WebP image, which this server cannot decode, so it cannot check it is a picture or fit it to the model"
                    : "it is not a PNG, JPEG or GIF image, the kinds this server can check");
        }
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format.reader);
        if (!readers.hasNext()) return Made.stays("this server has no reader for " + format.mediaType);
        ImageReader reader = readers.next();
        BufferedImage image;
        int width, height;
        Size target;
        try (var in = new FileImageInputStream(file.toFile())) {
            reader.setInput(in, true, true);
            width = reader.getWidth(0);
            height = reader.getHeight(0);
            if (width <= 0 || height <= 0) return Made.stays("it did not decode as an image");
            target = resizedSize(width, height, MAX_EDGE, MAX_TOKENS);
            ImageReadParam param = reader.getDefaultReadParam();
            int every = subsampling(width, height, target);
            if (every > 1) param.setSourceSubsampling(every, every, 0, 0);
            image = reader.read(0, param);
        } catch (IOException | RuntimeException notAnImage) {
            return Made.stays("it did not decode as an image");
        } finally {
            reader.dispose();
        }
        if (image == null) return Made.stays("it did not decode as an image");

        boolean fits = target.width() == width && target.height() == height;
        long size = Files.size(file);
        if (fits && format != Format.GIF && base64(size) <= MAX_BASE64) {
            byte[] bytes = Files.readAllBytes(file);
            if (format == Format.PNG ? onlyThePicturePng(bytes) : onlyThePictureJpeg(bytes)) {
                return Made.of(new Copy(format.mediaType, width, height, width, height, bytes, true));
            }
        }
        boolean alpha = image.getColorModel().hasAlpha() && !opaque(image);
        BufferedImage scaled = scale(image, target.width(), target.height(), alpha);
        byte[] png = png(scaled);
        if (base64(png.length) <= MAX_BASE64) {
            return Made.of(new Copy("image/png", target.width(), target.height(), width, height, png, false));
        }
        BufferedImage flat = alpha ? flatten(scaled) : scaled;
        for (float quality : JPEG_QUALITIES) {
            byte[] jpeg = jpeg(flat, quality);
            if (base64(jpeg.length) <= MAX_BASE64) {
                return Made.of(new Copy("image/jpeg", target.width(), target.height(), width, height, jpeg, false));
            }
        }
        return Made.stays("even as a JPEG its copy is larger than the cloud model takes for one image");
    }

    /** The format a file's first bytes say it is, of the three this code decodes; null for any other. */
    static Format sniff(byte[] head, int got) {
        if (got >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
                && head[4] == 0x0D && head[5] == 0x0A && head[6] == 0x1A && head[7] == 0x0A) {
            return Format.PNG;
        }
        if (got >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return Format.JPEG;
        }
        if (got >= 6) {
            String gif = new String(head, 0, 6, StandardCharsets.ISO_8859_1);
            if (gif.equals("GIF87a") || gif.equals("GIF89a")) return Format.GIF;
        }
        return null;
    }

    /** Whether the first bytes are a WebP's: "RIFF", a size, "WEBP". */
    static boolean webp(byte[] head, int got) {
        return got >= 12 && new String(head, 0, 4, StandardCharsets.ISO_8859_1).equals("RIFF")
                && new String(head, 8, 4, StandardCharsets.ISO_8859_1).equals("WEBP");
    }

    /** {@code bytes} as a request carries them, base64-encoded. */
    static long base64(long bytes) {
        return 4 * Math.ceilDiv(bytes, 3);
    }

    // ── Anthropic's rule: platform.claude.com/docs/en/build-with-claude/vision-coordinates ──

    /** Visual tokens consumed by an image: one token for each 28x28 pixel patch. */
    static int countImageTokens(int width, int height) {
        return Math.ceilDiv(width, 28) * Math.ceilDiv(height, 28);
    }

    /**
     * The size Claude resizes an image to before padding: unchanged when it fits the limits, else
     * the largest aspect-preserving size that does, the short edge rounded half to even as the live
     * API rounds it. Anthropic's reference implementation, as published.
     */
    static Size resizedSize(int width, int height, int maxEdge, int maxTokens) {
        if (fits(width, height, maxEdge, maxTokens)) {
            return new Size(width, height);
        }
        if (height > width) {
            Size rotated = resizedSize(height, width, maxEdge, maxTokens);
            return new Size(rotated.height(), rotated.width());
        }
        // Binary search along the long edge for the largest aspect-preserving size that fits.
        double aspectRatio = (double) width / height;
        int lo = 1; // lo always fits
        int hi = width; // hi never fits
        while (lo + 1 < hi) {
            int mid = (lo + hi) / 2;
            if (fits(mid, shortEdge(mid, aspectRatio), maxEdge, maxTokens)) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return new Size(lo, shortEdge(lo, aspectRatio));
    }

    private static boolean fits(int width, int height, int maxEdge, int maxTokens) {
        return Math.ceilDiv(width, 28) * 28 <= maxEdge
                && Math.ceilDiv(height, 28) * 28 <= maxEdge
                && countImageTokens(width, height) <= maxTokens;
    }

    private static int shortEdge(int longEdge, double aspectRatio) {
        return Math.max((int) Math.rint(longEdge / aspectRatio), 1);
    }

    // ── Decoding and encoding ──

    /**
     * Every how many pixels a large image is read: 1 for one at most twice the copy's longer edge,
     * else the fewest that read it at most that -- never less than the copy, which the reading is
     * then smoothed down to. A picture many times the size the model reads is not held whole in
     * memory for it.
     */
    static int subsampling(int width, int height, Size target) {
        int longest = Math.max(width, height);
        int targetLongest = Math.max(target.width(), target.height());
        return Math.max(1, Math.ceilDiv(longest, 2 * targetLongest));
    }

    /**
     * Whether every pixel of {@code image} is opaque: then its copy has no alpha channel, a quarter
     * fewer bytes. A palette's is taken as it says -- any entry not opaque, the picture not opaque.
     */
    private static boolean opaque(BufferedImage image) {
        var model = image.getColorModel();
        if (!model.hasAlpha()) return true;
        if (model instanceof java.awt.image.IndexColorModel) return model.getTransparency() == java.awt.Transparency.OPAQUE;
        var alpha = image.getAlphaRaster();
        if (alpha == null) return false;
        int max = (1 << model.getComponentSize(model.getNumComponents() - 1)) - 1;
        int w = image.getWidth(), h = image.getHeight();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            alpha.getSamples(0, y, w, 1, 0, row);
            for (int a : row) if (a != max) return false;
        }
        return true;
    }

    /**
     * {@code image} at {@code width} x {@code height}, 8 bits a channel: halved, smoothly, until it
     * is less than twice that, then drawn at that size -- bilinear at each step, which halving
     * makes an average of each pixel's neighbours. The same pixels in give the same pixels out.
     */
    static BufferedImage scale(BufferedImage image, int width, int height, boolean alpha) {
        int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage now = image;
        int w = image.getWidth(), h = image.getHeight();
        // Drawn once at least: an image of the copy's size still becomes 8 bits a channel.
        do {
            int nw = w > width ? Math.max(width, (w + 1) / 2) : width;
            int nh = h > height ? Math.max(height, (h + 1) / 2) : height;
            now = draw(now, nw, nh, type);
            w = nw;
            h = nh;
        } while (w != width || h != height);
        return now;
    }

    private static BufferedImage draw(BufferedImage image, int width, int height, int type) {
        BufferedImage out = new BufferedImage(width, height, type);
        Graphics2D g = out.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            g.drawImage(image, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** {@code image} on white, for a JPEG, which has no transparency. */
    private static BufferedImage flatten(BufferedImage image) {
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** A PNG of {@code image}, written in memory: ImageIO's own cache would write a file to the temp directory. */
    static byte[] png(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        var bytes = new ByteArrayOutputStream();
        try (var out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), writer.getDefaultWriteParam());
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    /** A JPEG of {@code image} at {@code quality}, written in memory. */
    static byte[] jpeg(BufferedImage image, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        var bytes = new ByteArrayOutputStream();
        try (var out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    // ── Nothing but the picture ──

    /**
     * The chunks a PNG may have for its own bytes to be the copy: the picture and how to draw it --
     * no text (tEXt, zTXt, iTXt), no EXIF (eXIf), no time (tIME), no colour profile (iCCP, which
     * names the device it came from), no animation (acTL, fcTL, fdAT), no chunk of an editor's own.
     */
    private static final Set<String> PICTURE_CHUNKS = Set.of("IHDR", "PLTE", "IDAT", "IEND", "tRNS", "cHRM",
            "gAMA", "sRGB", "sBIT", "bKGD", "pHYs", "hIST", "cICP");

    /** Whether a PNG's chunks are all {@link #PICTURE_CHUNKS}, each whole, and nothing follows its end. */
    static boolean onlyThePicturePng(byte[] b) {
        int i = 8;
        while (i + 12 <= b.length) {
            long length = ((b[i] & 0xFFL) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            if (length > b.length - i - 12L) return false;
            String type = new String(b, i + 4, 4, StandardCharsets.ISO_8859_1);
            if (!PICTURE_CHUNKS.contains(type)) return false;
            i += 12 + (int) length;
            if (type.equals("IEND")) return i == b.length;
        }
        return false;
    }

    /**
     * Whether a JPEG holds the picture and nothing else, and nothing follows its end: its tables,
     * frame and scans, a JFIF header with no thumbnail, Adobe's colour marker -- no EXIF or XMP
     * (APP1), colour profile (APP2), IPTC (APP13), any other application block, or comment.
     */
    static boolean onlyThePictureJpeg(byte[] b) {
        int i = 2;
        while (i + 1 < b.length) {
            if ((b[i] & 0xFF) != 0xFF) return false;
            int marker = b[i + 1] & 0xFF;
            if (marker == 0xFF) { i++; continue; }                  // fill
            if (marker == 0xD9) return i + 2 == b.length;            // the end, and nothing after it
            if (i + 4 > b.length) return false;
            int length = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            if (length < 2 || i + 2 + length > b.length) return false;
            if (!pictureSegment(marker, b, i + 4, length - 2)) return false;
            i += 2 + length;
            if (marker == 0xDA) {
                // The scan's coded data, to the next marker: a byte stuffed after 0xFF, a restart
                // marker and fill are part of it.
                while (i + 1 < b.length) {
                    if ((b[i] & 0xFF) != 0xFF) { i++; continue; }
                    int next = b[i + 1] & 0xFF;
                    if (next == 0x00 || next >= 0xD0 && next <= 0xD7) { i += 2; continue; }
                    if (next == 0xFF) { i++; continue; }
                    break;
                }
            }
        }
        return false;
    }

    private static boolean pictureSegment(int marker, byte[] b, int at, int length) {
        if (marker == 0xDB || marker == 0xC4 || marker == 0xDD || marker == 0xDA || marker == 0xCC) return true;
        // Start of frame: every kind but the three codes that are not one.
        if (marker >= 0xC0 && marker <= 0xCF) return marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
        if (marker == 0xE0) {
            // JFIF, version, units, densities, and a thumbnail of 0 x 0.
            return length == 14 && new String(b, at, 5, StandardCharsets.ISO_8859_1).equals("JFIF\0")
                    && b[at + 12] == 0 && b[at + 13] == 0;
        }
        if (marker == 0xEE) {
            // Adobe, version, two flags and the colour transform.
            return length == 12 && new String(b, at, 5, StandardCharsets.ISO_8859_1).equals("Adobe");
        }
        return false;
    }

    /** The hex SHA-256 of {@code bytes}. */
    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }
}
