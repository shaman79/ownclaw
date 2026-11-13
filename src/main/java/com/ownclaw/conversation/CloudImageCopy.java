package com.ownclaw.conversation;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.event.IIOReadProgressListener;
import javax.imageio.event.IIOReadUpdateListener;
import javax.imageio.event.IIOWriteProgressListener;
import javax.imageio.stream.ImageInputStreamImpl;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBufferInt;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The copy of a pasted image that the cloud model is sent: made once, when the image is uploaded,
 * and stored beside it ({@link FileStorageService#store}), so that every request of every later
 * turn sends the same bytes. The file as it was pasted is never touched: skills are handed that.
 * <p>
 * Only an image this code has decoded is one: a PNG, a baseline JPEG or a GIF by its first bytes,
 * its structure walked first ({@link #header}) and its pixels then read by the JDK's reader for that
 * format and no other -- a reader picked by ImageIO's own guess takes a text file for a WBMP.
 * Anything else stays private: a WebP, which the JDK cannot decode, and a progressive or other
 * multi-scan JPEG, whose reading takes memory in proportion to its whole declared size, however
 * small the file -- a 200-byte one declaring 30,000 x 30,000 pixels took a server down. Browsers
 * paste neither: they put a pasted image on the clipboard as a PNG.
 * <p>
 * The copy is always the decoded pixels, encoded again -- never the pasted bytes, whatever their
 * structure says: what leaves is the picture and nothing a file can carry beside it or hide in it.
 * A PNG or a GIF becomes a PNG; a JPEG a JPEG of high quality, as photographs are made; a PNG a
 * JPEG only when as a PNG it would be larger than one image may be. EXIF orientation is applied
 * first, so a phone's portrait photo is shown upright and its sizes are as a viewer shows it; grey
 * is copied by hand, as the JDK's drawing takes a PNG's grey for linear light and lightens it.
 * <p>
 * Its size is the resolution the model reads it at, by Anthropic's own rule for its high-resolution
 * tier (Claude 4.7 and later, claude-opus-5-5 among them): the largest size, aspect kept, whose
 * edges padded to 28 px are at most {@link #MAX_EDGE} and whose visual tokens are at most
 * {@link #MAX_TOKENS} ({@link #resizedSize}, the reference implementation of
 * platform.claude.com/docs/en/build-with-claude/vision-coordinates). The API scales a larger image
 * to exactly that, so the model is shown nothing less than it would read; a smaller one keeps its
 * size. That size keeps it within the API's other limits too: 8,000 px, 3,000 px in a request of
 * more than 20 images, and 10 MB base64 ({@link #MAX_BASE64}).
 * <p>
 * One check runs at a time, on a thread of its own, so uploads queue rather than multiply what
 * the server spends; and each is given {@link #CHECK_TIME}: past it the read is aborted -- by the
 * reading thread itself, the only one the JDK's JPEG reader takes an abort from -- and the file
 * stays private, whole, for skills. The file is read from memory, and a reader is handed only the
 * chunks it draws from. A declaration no screenshot or photo has -- more than {@link #MAX_PIXELS}
 * pixels, more than {@link #MAX_PARTS} parts beside the picture's data -- is not read at all: a
 * file of a few hundred bytes so made kept a reader busy for minutes. A large image is read at a
 * fraction of its pixels, so its reading holds no more than about four times the copy in memory.
 * <p>
 * What the pasted file carries beside the picture -- EXIF (a camera's, a time, a place), XMP,
 * IPTC, a colour profile, text, a comment, a thumbnail, more frames, bytes after its end -- is said
 * ({@link Copy#metadata}): skills are handed that file, so whatever they read from it is private.
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
     * A picture of at most 4,784 visual tokens -- 3.75 megapixels -- is a megabyte or two at the
     * first; the others are for a picture of noise, so that even that one can be sent.
     */
    static final float[] JPEG_QUALITIES = {0.95f, 0.92f, 0.85f, 0.75f, 0.6f};

    /**
     * How long checking one pasted image may take: decoding it, and encoding its copy. A screenshot
     * takes well under a second, a twelve-megapixel photo about two; past this the read is aborted
     * and the file stays private, whole -- skills are still given it. A bound on the server's time,
     * not on any content.
     */
    static final Duration CHECK_TIME = Duration.ofSeconds(30);

    /** Why a pasted file whose check ran out of time stays private. */
    static final String TOO_LONG = "it took too long to check, so it stays private -- skills still get it";

    /**
     * The memory a reader may take for the rows it holds at once (about three of the file's widest):
     * past it a PNG -- the one format whose width is not bounded at 65,535 -- is not read, and stays
     * private. Only a width of millions of pixels reaches it.
     */
    static final long ROW_MEMORY = 64L << 20;

    /**
     * The most pixels a pasted image may declare and be checked: 2^28, 268 million -- more than any
     * screen or camera makes (a 200-megapixel phone photo, a page-long screenshot are under it), and
     * read in a second or two. A crafted file declaring more is small and slow: one of 215 bytes
     * declaring 46,340 x 46,340 took a quarter of a minute to decode. A bound on the check, not on
     * the content: the file is kept, and skills are given it.
     */
    static final long MAX_PIXELS = 1L << 28;

    /**
     * The most parts a pasted image may hold beside its picture's data and be checked: chunks,
     * segments, sub-blocks. A screenshot or a photo holds tens; a crafted file of millions -- 95 MB of
     * empty PNG chunks, a GIF comment in one-byte sub-blocks -- kept a reader at them for minutes.
     */
    static final int MAX_PARTS = 10_000;

    /** Why a pasted image declaring more than {@link #MAX_PIXELS} stays private. */
    static final String TOO_MANY_PIXELS = "it is too large to check -- more than 268 million pixels, which no screen "
            + "or camera makes -- so it stays private; skills still get it";

    /** Why a pasted image of more than {@link #MAX_PARTS} parts stays private. */
    static final String TOO_MANY_PARTS = "it is too large to check -- more than 10,000 parts beside its picture's "
            + "data, which no screenshot or photo has -- so it stays private; skills still get it";

    /** One check at a time: uploads queue. */
    private static final ExecutorService CHECKER = Executors.newSingleThreadExecutor(daemon("pasted-image-check"));

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

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
     * @param mediaType      image/png or image/jpeg: what its bytes are
     * @param width          its width in pixels: what the model reads
     * @param height         its height in pixels
     * @param originalWidth  the pasted image's width as a viewer shows it -- its EXIF orientation applied
     * @param originalHeight the pasted image's height, the same way
     * @param bytes          its bytes
     * @param metadata       what the pasted file carries beside the picture, in words -- "EXIF data, a
     *                       colour profile" -- or null when it holds the picture alone
     */
    public record Copy(String mediaType, int width, int height, int originalWidth, int originalHeight,
                       byte[] bytes, String metadata) {

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

    /** Why a pasted image whose turn to be checked did not come by its upload's deadline stays private. */
    static final String NOT_IN_TIME = "other images were being checked and its turn did not come in time, so it "
            + "stays private -- skills still get it";

    /**
     * Each owner's turn at the checker: one check of an owner's is queued or running at a time, so
     * the queue holds no more checks than there are owners uploading -- an account's two hundred
     * pastes at once wait for their own turns, not ahead of everybody else's.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.Semaphore> TURNS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The deadline of an upload's checks, from now: {@link #CHECK_TIME}, for its wait for its turns
     * and its checks together. Every file of one upload is checked by the same deadline.
     */
    public static long deadline() {
        return System.nanoTime() + CHECK_TIME.toNanos();
    }

    /** The copy of the image in {@code file}, or why it stays private; one check at a time, within {@link #CHECK_TIME}. */
    public static Made of(Path file) {
        return of(file, "", deadline());
    }

    /** {@link #of(Path)}, given {@code limit} to check it in. */
    static Made of(Path file, Duration limit) {
        return of(file, "", System.nanoTime() + limit.toNanos());
    }

    /**
     * The copy of the image in {@code file}, uploaded by {@code owner}, or why it stays private --
     * by {@code deadline} ({@link System#nanoTime}), whatever happens: waiting for the owner's turn
     * and for the checks ahead of it included. A request thread is held no longer than that; a file
     * whose turn did not come stays private, and its queued check is cancelled.
     */
    public static Made of(Path file, String owner, long deadline) {
        var turn = TURNS.computeIfAbsent(owner == null ? "" : owner, k -> new java.util.concurrent.Semaphore(1));
        try {
            long left = deadline - System.nanoTime();
            if (left <= 0 || !turn.tryAcquire(left, TimeUnit.NANOSECONDS)) return Made.stays(NOT_IN_TIME);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Made.stays("its check was interrupted, so it stays private");
        }
        var check = new Check(file, deadline, turn);
        Future<Made> done;
        try {
            done = CHECKER.submit(check);
        } catch (RejectedExecutionException e) {
            check.endTurn();
            return Made.stays("it could not be checked, so it stays private");
        }
        try {
            return done.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // The check hears it on its own thread, where a reader can be stopped; one not begun never begins.
            check.cancelled = true;
            done.cancel(false);
            if (!check.started) check.endTurn();
            return Made.stays(e instanceof InterruptedException ? "its check was interrupted, so it stays private"
                    : check.started ? TOO_LONG : NOT_IN_TIME);
        } catch (ExecutionException e) {
            return Made.stays(check.late() ? TOO_LONG : "it could not be read as an image");
        }
    }

    /** What a check asks between the pieces of its work, and its reading between bytes. */
    interface Deadline {
        /** Throws {@link Late} once the check is past its time. */
        void inTime() throws IOException;

        Deadline NONE = () -> { };
    }

    /** A check past its time: what ends its reading, from the reading thread itself. */
    static final class Late extends IOException {
        Late() {
            super("past its time");
        }
    }

    /**
     * One check, on {@link #CHECKER}. Its time is kept by the thread doing the work, never by
     * another: the JDK's JPEG reader and writer throw when another thread aborts them, and its PNG
     * and GIF readers forget an abort that came before their read began. So the bytes are read from
     * memory through a stream that asks the time ({@link Reading}), the reader and the writer hear
     * it on their own progress ({@link Stopper}), and the work asks it between its pieces.
     */
    private static final class Check implements Callable<Made>, Deadline {
        final Path file;
        final long deadline;
        private final java.util.concurrent.Semaphore turn;
        private final java.util.concurrent.atomic.AtomicBoolean turnEnded = new java.util.concurrent.atomic.AtomicBoolean();
        volatile boolean cancelled;
        volatile boolean started;

        Check(Path file, long deadline, java.util.concurrent.Semaphore turn) {
            this.file = file;
            this.deadline = deadline;
            this.turn = turn;
        }

        boolean late() {
            return cancelled || System.nanoTime() - deadline >= 0;
        }

        /** The owner's next check may queue: once, whoever gets here first -- the check, or its waiter. */
        void endTurn() {
            if (turnEnded.compareAndSet(false, true)) turn.release();
        }

        @Override
        public void inTime() throws IOException {
            if (late()) throw new Late();
        }

        @Override
        public Made call() {
            started = true;
            try {
                if (late()) return Made.stays(TOO_LONG);
                Made made = make(file, this);
                return late() ? Made.stays(TOO_LONG) : made;
            } catch (IOException | RuntimeException e) {
                return Made.stays(late() ? TOO_LONG : "it did not decode as an image");
            } catch (OutOfMemoryError e) {
                // One allocation the heap could not give: the check ends, the server goes on.
                return Made.stays("reading it took more memory than this server has for one picture, so it "
                        + "stays private -- skills still get it");
            } finally {
                endTurn();
            }
        }
    }

    /**
     * Stops a read or a write from its own thread once its check is past its time: each row a
     * reader decodes and each a writer encodes is reported here, and an abort from there is one the
     * JDK's readers and writers all take.
     */
    private static final class Stopper implements IIOReadProgressListener, IIOReadUpdateListener,
            IIOWriteProgressListener {
        private final Check check;

        Stopper(Check check) {
            this.check = check;
        }

        private void stopIfLate(ImageReader reader) {
            if (check.late()) reader.abort();
        }

        private void stopIfLate(ImageWriter writer) {
            if (check.late()) writer.abort();
        }

        @Override public void imageStarted(ImageReader source, int imageIndex) { stopIfLate(source); }
        @Override public void imageProgress(ImageReader source, float percentageDone) { stopIfLate(source); }
        @Override public void imageUpdate(ImageReader source, BufferedImage image, int minX, int minY, int width,
                                          int height, int periodX, int periodY, int[] bands) { stopIfLate(source); }
        @Override public void passStarted(ImageReader source, BufferedImage image, int pass, int minPass, int maxPass,
                                          int minX, int minY, int periodX, int periodY, int[] bands) { stopIfLate(source); }
        @Override public void imageStarted(ImageWriter source, int imageIndex) { stopIfLate(source); }
        @Override public void imageProgress(ImageWriter source, float percentageDone) { stopIfLate(source); }

        @Override public void sequenceStarted(ImageReader source, int minIndex) { }
        @Override public void sequenceComplete(ImageReader source) { }
        @Override public void imageComplete(ImageReader source) { }
        @Override public void thumbnailStarted(ImageReader source, int imageIndex, int thumbnailIndex) { }
        @Override public void thumbnailProgress(ImageReader source, float percentageDone) { }
        @Override public void thumbnailComplete(ImageReader source) { }
        @Override public void readAborted(ImageReader source) { }
        @Override public void passComplete(ImageReader source, BufferedImage image) { }
        @Override public void thumbnailPassStarted(ImageReader source, BufferedImage thumbnail, int pass, int minPass,
                                                   int maxPass, int minX, int minY, int periodX, int periodY,
                                                   int[] bands) { }
        @Override public void thumbnailUpdate(ImageReader source, BufferedImage thumbnail, int minX, int minY,
                                              int width, int height, int periodX, int periodY, int[] bands) { }
        @Override public void thumbnailPassComplete(ImageReader source, BufferedImage thumbnail) { }
        @Override public void imageComplete(ImageWriter source) { }
        @Override public void thumbnailStarted(ImageWriter source, int imageIndex, int thumbnailIndex) { }
        @Override public void thumbnailProgress(ImageWriter source, float percentageDone) { }
        @Override public void thumbnailComplete(ImageWriter source) { }
        @Override public void writeAborted(ImageWriter source) { }
    }

    /**
     * The file's bytes, from memory, for a reader: each read asks the check's time now and then, so
     * a reader that takes its input a byte at a time -- the PNG and GIF readers read every chunk's
     * and sub-block's header so -- stops when the check is past its time, wherever it is.
     */
    private static final class Reading extends ImageInputStreamImpl {
        private final byte[] data;
        private final Deadline deadline;
        private int reads;

        Reading(byte[] data, Deadline deadline) {
            this.data = data;
            this.deadline = deadline;
        }

        @Override
        public int read() throws IOException {
            checkClosed();
            tick();
            bitOffset = 0;
            return streamPos < data.length ? data[(int) streamPos++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            checkClosed();
            tick();
            java.util.Objects.checkFromIndexSize(off, len, b.length);
            bitOffset = 0;
            if (len == 0) return 0;
            if (streamPos >= data.length) return -1;
            int n = (int) Math.min(len, data.length - streamPos);
            System.arraycopy(data, (int) streamPos, b, off, n);
            streamPos += n;
            return n;
        }

        @Override
        public long length() {
            return data.length;
        }

        private void tick() throws IOException {
            if ((++reads & 0xFF) == 0) deadline.inTime();
        }
    }

    /** The copy, made on the check's thread. */
    private static Made make(Path file, Check check) throws IOException {
        // In memory: an upload is at most 100 MB, and one check runs at a time.
        byte[] data = Files.readAllBytes(file);
        check.inTime();
        Header header = header(data, check);
        if (header.refused() != null) return Made.stays(header.refused());
        if (header.picture() != null) data = pictureAlone(data, header.picture());
        check.inTime();
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(header.format().reader);
        if (!readers.hasNext()) return Made.stays("this server has no reader for " + header.format().mediaType);
        ImageReader reader = readers.next();
        var stopper = new Stopper(check);
        reader.addIIOReadProgressListener(stopper);
        reader.addIIOReadUpdateListener(stopper);
        BufferedImage image;
        int width, height;
        Size target;
        try (var in = new Reading(data, check)) {
            reader.setInput(in, true, true);
            width = reader.getWidth(0);
            height = reader.getHeight(0);
            check.inTime();
            if (width <= 0 || height <= 0) return Made.stays("it did not decode as an image");
            if ((long) width * header.bytesPerPixel() * 3 > ROW_MEMORY) {
                return Made.stays("it is wider than this server can read a row of, so it stays private -- skills "
                        + "still get it");
            }
            boolean turned = header.orientation() >= 5;
            target = resizedSize(turned ? height : width, turned ? width : height, MAX_EDGE, MAX_TOKENS);
            ImageReadParam param = reader.getDefaultReadParam();
            int every = subsampling(width, height, target, header.pixelBytes());
            if (every > 1) param.setSourceSubsampling(every, every, 0, 0);
            // The PNG and GIF readers clear an abort when their read begins: past its time, it is
            // not begun -- and once begun, the Stopper hears every row.
            check.inTime();
            image = reader.read(0, param);
        } catch (IOException | RuntimeException notAnImage) {
            return Made.stays(check.late() ? TOO_LONG : "it did not decode as an image");
        } finally {
            reader.dispose();
        }
        // An aborted read returns what it had decoded: none of it is a copy.
        if (check.late()) return Made.stays(TOO_LONG);
        if (image == null) return Made.stays("it did not decode as an image");
        // Read at the copy's size or less -- a 16-bit picture is (subsampling): the copy is what was
        // read, never drawn larger than that.
        boolean sideways = header.orientation() >= 5;
        int readWidth = sideways ? image.getHeight() : image.getWidth();
        int readHeight = sideways ? image.getWidth() : image.getHeight();
        if (readWidth < target.width() || readHeight < target.height()) {
            target = new Size(Math.min(readWidth, target.width()), Math.min(readHeight, target.height()));
        }

        // Each step's input let go before the next is made, and turned last, at the copy's size:
        // turned first, a 16-bit photo was held three times over at its whole size.
        BufferedImage pixels = normalized(image);
        image = null;
        boolean turned = header.orientation() >= 5;
        int shownWidth = turned ? height : width, shownHeight = turned ? width : height;
        check.inTime();
        BufferedImage scaled = scale(pixels, turned ? target.height() : target.width(), turned ? target.width() : target.height());
        pixels = null;
        scaled = oriented(scaled, header.orientation());
        check.inTime();

        // In the original's family: a photograph stays a JPEG, a screenshot a PNG.
        if (header.format() != Format.JPEG) {
            byte[] png = png(scaled, stopper);
            check.inTime();
            if (base64(png.length) <= MAX_BASE64) {
                return Made.of(new Copy("image/png", target.width(), target.height(), shownWidth, shownHeight, png,
                        header.metadata()));
            }
        }
        BufferedImage flat = scaled.getType() == BufferedImage.TYPE_INT_ARGB ? flatten(scaled) : scaled;
        for (float quality : JPEG_QUALITIES) {
            byte[] jpeg = jpeg(flat, quality, stopper);
            check.inTime();
            if (base64(jpeg.length) <= MAX_BASE64) {
                return Made.of(new Copy("image/jpeg", target.width(), target.height(), shownWidth, shownHeight, jpeg,
                        header.metadata()));
            }
        }
        return Made.stays("even as a JPEG its copy is larger than the cloud model takes for one image");
    }

    /** {@code bytes} as a request carries them, base64-encoded. */
    static long base64(long bytes) {
        return 4 * Math.ceilDiv(bytes, 3);
    }

    // ── The file's structure, before any pixel ──

    /**
     * What a file's own structure says, walked once from its start, and none of its pixels decoded:
     * its format, how its EXIF says to turn it, what it carries beside the picture -- or why it is not
     * read at all.
     *
     * @param bytesPerPixel the most a reader holds of one pixel of a row, for {@link #ROW_MEMORY}
     * @param pixelBytes    the bytes of one pixel as the reader decodes it: 8 for a 16-bit RGBA PNG
     * @param orientation   1 to 8, as EXIF numbers them; 1 when it says none
     * @param metadata      what it carries beside the picture, in words, or null
     * @param refused       why it is not read, or null
     * @param picture       of a PNG that holds more than its picture, the byte ranges -- start, end, start,
     *                      end... -- of the chunks a reader needs to draw it, which alone it is given:
     *                      of a palette image the JDK's reader parses every chunk it is handed, an sPLT
     *                      of 95 MB into arrays of hundreds, and inflates a colour profile whatever it
     *                      inflates to. Null when the file is that already, and for any other format.
     */
    record Header(Format format, int bytesPerPixel, int pixelBytes, int orientation, String metadata, String refused,
                  int[] picture) {
        static Header refused(String why) {
            return new Header(null, 0, 0, 1, null, why, null);
        }
    }

    /** The PNG chunks a reader draws the picture from: its header, palette, transparency, data and end. */
    private static final Set<String> DRAWN_FROM = Set.of("IHDR", "PLTE", "tRNS", "IDAT", "IEND");

    /** {@code data} with only the byte ranges {@code picture} names, after a PNG's signature. */
    static byte[] pictureAlone(byte[] data, int[] picture) {
        int size = 8;
        for (int i = 0; i < picture.length; i += 2) size += picture[i + 1] - picture[i];
        byte[] out = java.util.Arrays.copyOf(data, size);
        int at = 8;
        for (int i = 0; i < picture.length; i += 2) {
            System.arraycopy(data, picture[i], out, at, picture[i + 1] - picture[i]);
            at += picture[i + 1] - picture[i];
        }
        return out;
    }

    /** {@code file}'s {@link Header}. */
    static Header header(Path file) throws IOException {
        return header(Files.readAllBytes(file), Deadline.NONE);
    }

    /** The {@link Header} of a file of these bytes, walked within {@code deadline}. */
    static Header header(byte[] data, Deadline deadline) throws IOException {
        Format format = sniff(data, data.length);
        if (format == null) {
            return Header.refused(webp(data, data.length)
                    ? "it is a WebP image, which this server cannot decode, so it cannot check it is a picture or fit it to the model"
                    : "it is not a PNG, JPEG or GIF image, the kinds this server can check");
        }
        var in = new Bytes(data, deadline);
        try {
            return switch (format) {
                case PNG -> png(in);
                case JPEG -> jpeg(in);
                case GIF -> gif(in);
            };
        } catch (EOFException cut) {
            return Header.refused("it is not a whole image: the file ends early");
        }
    }

    /** Whether a picture of {@code width} x {@code height} is more than {@link #MAX_PIXELS}. */
    private static boolean tooManyPixels(long width, long height) {
        return width * height > MAX_PIXELS;
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

    /**
     * The chunks a PNG holds that are its picture and how to draw it, each of the length the format
     * gives it where it gives one: anything else -- text (tEXt, zTXt, iTXt), EXIF (eXIf), a time
     * (tIME), a colour profile (iCCP, which names the device it came from), more frames (acTL, fcTL,
     * fdAT), a chunk of a program's own, a setting of the wrong length -- is something beside it.
     */
    private static final Set<String> PICTURE_CHUNKS = Set.of("IHDR", "PLTE", "IDAT", "IEND", "tRNS", "cHRM",
            "gAMA", "sRGB", "sBIT", "bKGD", "pHYs", "hIST", "cICP");

    /** The PNG settings of one length only. */
    private static final Map<String, Integer> PNG_LENGTHS = Map.of("gAMA", 4, "cHRM", 32, "sRGB", 1, "pHYs", 9, "cICP", 4);

    /** An IDAT chunk shorter than this, but for the last, is one part beside the picture's data ({@link #MAX_PARTS}). */
    private static final int SHORT_IDAT = 1024;

    private static Header png(Bytes in) throws IOException {
        in.skip(8);
        var beside = new LinkedHashSet<String>();
        int colorType = -1, bitDepth = 8, orientation = 1, parts = 0;
        long lastIdat = -1;
        var drawn = new java.util.ArrayList<Integer>();
        boolean more = false;
        while (true) {
            int start = in.position();
            long length = in.u32();
            String type = in.ascii(4);
            if (DRAWN_FROM.contains(type) && length <= in.remaining() - 4) {
                drawn.add(start);
                drawn.add((int) (start + 12 + length));
            } else {
                more = true;
            }
            if (type.equals("IDAT")) {
                // Real encoders write it in runs of kilobytes; thousands of tiny ones are made to be slow.
                if (lastIdat >= 0 && lastIdat < SHORT_IDAT) parts++;
                lastIdat = length;
            } else {
                parts++;
            }
            if (parts > MAX_PARTS) return Header.refused(TOO_MANY_PARTS);
            switch (type) {
                case "IHDR" -> {
                    if (length != 13) return Header.refused("it is not a PNG the server can read: its header is broken");
                    long width = in.u32(), height = in.u32();
                    if (tooManyPixels(width, height)) return Header.refused(TOO_MANY_PIXELS);
                    bitDepth = in.u8();
                    colorType = in.u8();
                    in.skip(3 + 4);
                    continue;
                }
                case "IEND" -> {
                    in.skip(length + 4);
                    if (in.remaining() > 0) beside.add("bytes after the picture's end");
                    int samples = switch (colorType) {
                        case 0, 3 -> 1;
                        case 4 -> 2;
                        case 2 -> 3;
                        default -> 4;
                    };
                    return new Header(Format.PNG, samples * Math.max(1, bitDepth / 8), samples * (bitDepth == 16 ? 2 : 1),
                            orientation, words(beside), null,
                            more ? drawn.stream().mapToInt(Integer::intValue).toArray() : null);
                }
                case "eXIf" -> {
                    byte[] exif = in.readUpTo((int) Math.min(length, 1 << 20));
                    in.skip(length - exif.length + 4);
                    orientation = orientation(exif, 0);
                    beside.add("EXIF data");
                    continue;
                }
                default -> { }
            }
            if (!PICTURE_CHUNKS.contains(type)) {
                beside.add(switch (type) {
                    case "tEXt", "zTXt", "iTXt" -> "text";
                    case "iCCP" -> "a colour profile";
                    case "tIME" -> "a time stamp";
                    case "acTL", "fcTL", "fdAT" -> "more frames";
                    default -> "data of another program";
                });
            } else if (!fits(type, length, colorType)) {
                beside.add("data where a setting of the picture belongs");
            }
            in.skip(length + 4);
        }
    }

    /** Whether a picture chunk is of the length the format gives it. */
    private static boolean fits(String type, long length, int colorType) {
        Integer exact = PNG_LENGTHS.get(type);
        if (exact != null) return length == exact;
        return switch (type) {
            case "PLTE" -> length % 3 == 0 && length <= 768;
            case "tRNS" -> colorType == 3 ? length <= 256 : colorType == 0 ? length == 2 : colorType == 2 && length == 6;
            case "bKGD" -> length == 1 || length == 2 || length == 6;
            case "sBIT" -> length >= 1 && length <= 4;
            case "hIST" -> length % 2 == 0 && length <= 512;
            default -> true;
        };
    }

    private static Header jpeg(Bytes in) throws IOException {
        in.skip(2);
        var beside = new LinkedHashSet<String>();
        int frame = -1, components = 0, scans = 0, orientation = 1, parts = 0;
        boolean interleaved = true;
        while (true) {
            int b = in.read();
            if (b == -1) throw new EOFException();
            if (b != 0xFF) return Header.refused("it is not a JPEG the server can read: its structure is broken");
            int marker;
            do {
                marker = in.read();
            } while (marker == 0xFF);
            if (marker == -1) throw new EOFException();
            if (marker == 0xD9) {
                if (in.remaining() > 0) beside.add("bytes after the picture's end");
                break;
            }
            if (marker == 0x01 || marker >= 0xD0 && marker <= 0xD7) continue;
            if (++parts > MAX_PARTS) return Header.refused(TOO_MANY_PARTS);
            int length = in.u16() - 2;
            if (length < 0) return Header.refused("it is not a JPEG the server can read: its structure is broken");
            if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                if (frame != -1) return Header.refused("it is not a baseline JPEG: it holds more than one frame");
                frame = marker;
                if (length < 6) return Header.refused("it is not a JPEG the server can read: its frame is broken");
                in.skip(1);
                int height = in.u16(), width = in.u16();
                if (height == 0) return Header.refused("it is not a baseline JPEG, the kind browsers paste");
                if (tooManyPixels(width, height)) return Header.refused(TOO_MANY_PIXELS);
                components = in.u8();
                in.skip(length - 6);
            } else if (marker == 0xDA) {
                int inScan = in.u8();
                in.skip(length - 1);
                scans++;
                if (inScan != components) interleaved = false;
                in.skipScan();
            } else if (marker >= 0xE0 && marker <= 0xEF || marker == 0xFE) {
                byte[] data = in.readFully(length);
                String what = segment(marker, data);
                if (marker == 0xE1 && startsWith(data, "Exif\0\0")) orientation = orientation(data, 6);
                if (what != null) beside.add(what);
            } else if (marker == 0xC4 || marker == 0xDB || marker == 0xDD || marker == 0xCC) {
                in.skip(length);
            } else {
                return Header.refused("it is not a baseline JPEG, the kind browsers paste");
            }
        }
        // Baseline and extended sequential (SOF0, SOF1) in one interleaved scan: read row by row. A
        // progressive, lossless, hierarchical or arithmetic-coded frame -- or a sequential one in
        // several scans -- is read into buffers of its whole declared size first.
        if (frame != 0xC0 && frame != 0xC1) {
            return Header.refused("it is a progressive or other special JPEG, which browsers never paste and which takes "
                    + "memory in proportion to its whole size to read, so it stays private -- skills still get it");
        }
        if (scans != 1 || !interleaved) {
            return Header.refused("it is a JPEG in several scans, which browsers never paste and which takes memory in "
                    + "proportion to its whole size to read, so it stays private -- skills still get it");
        }
        return new Header(Format.JPEG, Math.max(1, components) * 16, Math.max(1, components), orientation, words(beside),
                null, null);
    }

    /** What a JPEG application segment or comment is beside the picture, or null for the picture's own. */
    private static String segment(int marker, byte[] data) {
        return switch (marker) {
            // JFIF with no thumbnail is the picture's own: its densities.
            case 0xE0 -> startsWith(data, "JFIF\0") && data.length == 14 && data[12] == 0 && data[13] == 0 ? null
                    : startsWith(data, "JFIF\0") || startsWith(data, "JFXX\0") ? "a thumbnail" : "data of another program";
            case 0xE1 -> startsWith(data, "Exif\0\0") ? "EXIF data" : startsWith(data, "http://ns.adobe.com/xap/") ? "XMP data"
                    : "data of another program";
            case 0xE2 -> startsWith(data, "ICC_PROFILE\0") ? "a colour profile" : "data of another program";
            case 0xED -> "IPTC data";
            // Adobe's colour transform is how to read the picture.
            case 0xEE -> startsWith(data, "Adobe") && data.length == 12 ? null : "data of another program";
            case 0xFE -> "a comment";
            default -> "data of another program";
        };
    }

    private static Header gif(Bytes in) throws IOException {
        in.skip(6);
        in.skip(4);
        int packed = in.u8();
        in.skip(2);
        if ((packed & 0x80) != 0) in.skip(3L << ((packed & 7) + 1));
        var beside = new LinkedHashSet<String>();
        int images = 0;
        int[] parts = {0};
        while (true) {
            int b = in.read();
            if (b == -1) throw new EOFException();
            if (b == 0x3B) {
                if (in.remaining() > 0) beside.add("bytes after the picture's end");
                break;
            }
            if (b == 0x2C) {
                images++;
                in.skip(4);
                int width = in.u16le(), height = in.u16le();
                if (images == 1 && tooManyPixels(width, height)) return Header.refused(TOO_MANY_PIXELS);
                int local = in.u8();
                if ((local & 0x80) != 0) in.skip(3L << ((local & 7) + 1));
                in.skip(1);
                // The picture's own data, in sub-blocks of 255 but its last; many short ones are made to be slow.
                if (!in.skipSubBlocks(parts, true)) return Header.refused(TOO_MANY_PARTS);
            } else if (b == 0x21) {
                int label = in.u8();
                parts[0]++;
                if (label == 0xF9) {
                    if (!in.skipSubBlocks(parts, false)) return Header.refused(TOO_MANY_PARTS);
                } else if (label == 0xFF) {
                    int size = in.u8();
                    String id = new String(in.readFully(size), StandardCharsets.ISO_8859_1);
                    if (!id.equals("NETSCAPE2.0") && !id.equals("ANIMEXTS1.0")) beside.add("data of another program");
                    if (!in.skipSubBlocks(parts, false)) return Header.refused(TOO_MANY_PARTS);
                } else {
                    beside.add(label == 0xFE ? "a comment" : label == 0x01 ? "text" : "data of another program");
                    if (!in.skipSubBlocks(parts, false)) return Header.refused(TOO_MANY_PARTS);
                }
            } else {
                return Header.refused("it is not a GIF the server can read: its structure is broken");
            }
        }
        if (images > 1) beside.add("more frames");
        return new Header(Format.GIF, 4, 1, 1, words(beside), null, null);
    }

    private static String words(Set<String> beside) {
        return beside.isEmpty() ? null : String.join(", ", beside);
    }

    private static boolean startsWith(byte[] data, String prefix) {
        if (data.length < prefix.length()) return false;
        for (int i = 0; i < prefix.length(); i++) if ((data[i] & 0xFF) != prefix.charAt(i)) return false;
        return true;
    }

    /**
     * The Orientation tag (0x0112) of the TIFF structure EXIF is, at {@code at} in {@code data}: 1 to
     * 8, or 1 when there is none or it cannot be read.
     */
    static int orientation(byte[] data, int at) {
        try {
            boolean little;
            if (data[at] == 'I' && data[at + 1] == 'I') little = true;
            else if (data[at] == 'M' && data[at + 1] == 'M') little = false;
            else return 1;
            if (u16(data, at + 2, little) != 42) return 1;
            long ifd = u32(data, at + 4, little);
            int entries = u16(data, (int) (at + ifd), little);
            for (int i = 0; i < entries; i++) {
                int e = (int) (at + ifd + 2 + 12L * i);
                if (u16(data, e, little) == 0x0112 && u16(data, e + 2, little) == 3) {
                    int value = u16(data, e + 8, little);
                    return value >= 1 && value <= 8 ? value : 1;
                }
            }
        } catch (IndexOutOfBoundsException | NegativeArraySizeException cut) {
            return 1;
        }
        return 1;
    }

    private static int u16(byte[] d, int at, boolean little) {
        return little ? (d[at] & 0xFF) | (d[at + 1] & 0xFF) << 8 : (d[at] & 0xFF) << 8 | (d[at + 1] & 0xFF);
    }

    private static long u32(byte[] d, int at, boolean little) {
        return little ? (u16(d, at, true) & 0xFFFFL) | (long) u16(d, at + 2, true) << 16
                : (long) u16(d, at, false) << 16 | (u16(d, at + 2, false) & 0xFFFFL);
    }

    /** A file's bytes, from memory, walked from its start; its check's time asked now and then. */
    private static final class Bytes {
        private final byte[] data;
        private final Deadline deadline;
        private int at;
        private int steps;

        Bytes(byte[] data, Deadline deadline) {
            this.data = data;
            this.deadline = deadline;
        }

        private void tick() throws IOException {
            if ((++steps & 0xFFF) == 0) deadline.inTime();
        }

        int remaining() {
            return data.length - at;
        }

        int position() {
            return at;
        }

        int read() throws IOException {
            tick();
            return at < data.length ? data[at++] & 0xFF : -1;
        }

        int u8() throws IOException {
            int b = read();
            if (b == -1) throw new EOFException();
            return b;
        }

        int u16() throws IOException {
            return u8() << 8 | u8();
        }

        int u16le() throws IOException {
            return u8() | u8() << 8;
        }

        long u32() throws IOException {
            return (long) u16() << 16 | u16();
        }

        String ascii(int n) throws IOException {
            return new String(readFully(n), StandardCharsets.ISO_8859_1);
        }

        byte[] readUpTo(int n) throws IOException {
            tick();
            int m = Math.min(n, remaining());
            byte[] out = java.util.Arrays.copyOfRange(data, at, at + m);
            at += m;
            return out;
        }

        byte[] readFully(int n) throws IOException {
            if (n > remaining()) throw new EOFException();
            return readUpTo(n);
        }

        void skip(long n) throws IOException {
            tick();
            if (n < 0 || n > remaining()) throw new EOFException();
            at += (int) n;
        }

        /**
         * A GIF's data sub-blocks, to the one of length 0, each counted in {@code parts} -- of the
         * picture's own data ({@code picture}) only one shorter than 255 that is not its last: false
         * once they are more than {@link #MAX_PARTS}.
         */
        boolean skipSubBlocks(int[] parts, boolean picture) throws IOException {
            int n = u8();
            while (n != 0) {
                skip(n);
                int next = u8();
                if (!picture || n < 255 && next != 0) parts[0]++;
                if (parts[0] > MAX_PARTS) return false;
                n = next;
            }
            return true;
        }

        /**
         * A JPEG scan's coded data, up to the marker after it, which is put back: a byte 0 stuffed after
         * 0xFF, a restart marker and fill are part of the data.
         */
        void skipScan() throws IOException {
            while (true) {
                int b = read();
                if (b == -1) throw new EOFException();
                if (b != 0xFF) continue;
                int next = read();
                while (next == 0xFF) next = read();
                if (next == -1) throw new EOFException();
                if (next == 0x00 || next >= 0xD0 && next <= 0xD7) continue;
                at -= 2;
                return;
            }
        }
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
     * memory for it. One whose pixels are more than 4 bytes as decoded -- 16 bits a sample -- is
     * read at the copy's size or under it, and its copy is what was read: at twice that, a 16-bit
     * photo of 180 KB took 120 MB to read. Pastes are 8-bit: screenshots are.
     */
    static int subsampling(int width, int height, Size target, int pixelBytes) {
        int longest = Math.max(width, height);
        int targetLongest = Math.max(target.width(), target.height());
        return Math.max(1, Math.ceilDiv(longest, (pixelBytes > 4 ? 1 : 2) * targetLongest));
    }

    /**
     * {@code image} as 8-bit sRGB, with alpha only where a pixel is not opaque. Grey is copied by
     * hand, as PNG viewers show it: drawn by Java2D, a PNG's grey with alpha -- whose colour space the
     * JDK gives as linear grey -- came out lighter, 128 as 188.
     */
    static BufferedImage normalized(BufferedImage image) {
        ColorModel model = image.getColorModel();
        boolean alpha = model.hasAlpha() && !opaque(image);
        int w = image.getWidth(), h = image.getHeight();
        BufferedImage out = new BufferedImage(w, h, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        if (!(model instanceof IndexColorModel) && model.getColorSpace().getType() == ColorSpace.TYPE_GRAY) {
            Raster raster = image.getRaster();
            boolean withAlpha = raster.getNumBands() > 1;
            int greyBits = model.getComponentSize(0);
            int alphaBits = withAlpha ? model.getComponentSize(1) : 8;
            int[] grey = new int[w], a = new int[w], argb = new int[w];
            for (int y = 0; y < h; y++) {
                raster.getSamples(0, y, w, 1, 0, grey);
                if (withAlpha) raster.getSamples(0, y, w, 1, 1, a);
                for (int x = 0; x < w; x++) {
                    int g = eightBits(grey[x], greyBits);
                    int av = withAlpha ? eightBits(a[x], alphaBits) : 255;
                    argb[x] = av << 24 | g << 16 | g << 8 | g;
                }
                out.setRGB(0, y, w, 1, argb, 0, w);
            }
            return out;
        }
        Graphics2D g = out.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static int eightBits(int value, int bits) {
        if (bits == 8) return value;
        if (bits == 16) return value >>> 8;
        int max = (1 << bits) - 1;
        return (value * 255 + max / 2) / max;
    }

    /**
     * Whether every pixel of {@code image} is opaque: then its copy has no alpha channel, a quarter
     * fewer bytes. A palette's is taken as it says -- any entry not opaque, the picture not opaque.
     */
    private static boolean opaque(BufferedImage image) {
        var model = image.getColorModel();
        if (!model.hasAlpha()) return true;
        if (model instanceof IndexColorModel) return model.getTransparency() == java.awt.Transparency.OPAQUE;
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
     * {@code image} turned as its EXIF orientation says a viewer shows it: 2 mirrored, 3 turned half
     * round, 4 flipped, 5 to 8 turned a quarter (mirrored or not), width and height swapped. Pixel
     * for pixel; 1, or any other, as it is.
     */
    static BufferedImage oriented(BufferedImage image, int orientation) {
        if (orientation < 2 || orientation > 8) return image;
        int w = image.getWidth(), h = image.getHeight();
        boolean swap = orientation >= 5;
        int ow = swap ? h : w;
        BufferedImage out = new BufferedImage(ow, swap ? w : h, image.getType());
        int[] from = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        int[] to = ((DataBufferInt) out.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int nx, ny;
                switch (orientation) {
                    case 2 -> { nx = w - 1 - x; ny = y; }
                    case 3 -> { nx = w - 1 - x; ny = h - 1 - y; }
                    case 4 -> { nx = x; ny = h - 1 - y; }
                    case 5 -> { nx = y; ny = x; }
                    case 6 -> { nx = h - 1 - y; ny = x; }
                    case 7 -> { nx = h - 1 - y; ny = w - 1 - x; }
                    default -> { nx = y; ny = w - 1 - x; }
                }
                to[ny * ow + nx] = from[y * w + x];
            }
        }
        return out;
    }

    /**
     * {@code image}, 8 bits a channel ({@link #normalized}), at {@code width} x {@code height}: halved,
     * smoothly, until it is less than twice that, then drawn at that size -- bilinear at each step,
     * which halving makes an average of each pixel's neighbours. The same pixels in give the same
     * pixels out.
     */
    static BufferedImage scale(BufferedImage image, int width, int height) {
        int type = image.getType();
        BufferedImage now = image;
        int w = image.getWidth(), h = image.getHeight();
        while (w != width || h != height) {
            int nw = w > width ? Math.max(width, (w + 1) / 2) : width;
            int nh = h > height ? Math.max(height, (h + 1) / 2) : height;
            now = draw(now, nw, nh, type);
            w = nw;
            h = nh;
        }
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
        return png(image, null);
    }

    private static byte[] png(BufferedImage image, Stopper stopper) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        if (stopper != null) writer.addIIOWriteProgressListener(stopper);
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
        return jpeg(image, quality, null);
    }

    private static byte[] jpeg(BufferedImage image, float quality, Stopper stopper) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        if (stopper != null) writer.addIIOWriteProgressListener(stopper);
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

    /** The hex SHA-256 of {@code bytes}. */
    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }
}
