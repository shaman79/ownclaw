package com.ownclaw.conversation;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Random;

/** Pictures for the tests of pasted images, made here and never read from anywhere. */
public final class TestImages {

    static {
        // Before anything touches AWT: the suite runs where no display is.
        System.setProperty("java.awt.headless", "true");
    }

    private TestImages() {}

    /** A picture with text on it, as a screenshot is: a documentation address, no one's. */
    public static BufferedImage screenshot(int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLACK);
        g.drawString("router 192.0.2.1 admin page", 10, 20);
        g.fillRect(width / 3, height / 3, width / 3, height / 3);
        g.dispose();
        return image;
    }

    /** Noise: what a PNG cannot make small. */
    public static BufferedImage noise(int width, int height, long seed) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var r = new Random(seed);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.setRGB(x, y, r.nextInt(0x1000000));
        return image;
    }

    /** {@code image} encoded as {@code format}: png, jpeg, gif. */
    public static byte[] encoded(BufferedImage image, String format) throws Exception {
        var out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, out)) throw new IllegalStateException("no writer for " + format);
        return out.toByteArray();
    }

    /** A PNG screenshot of this size. */
    public static byte[] png(int width, int height) throws Exception {
        return encoded(screenshot(width, height), "png");
    }

    // ── Files written by hand: what no encoder here would write ──

    /** A PNG of these chunks after its signature: {type, data} pairs, each with its CRC. */
    public static byte[] pngOf(Object... chunks) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
        for (int i = 0; i < chunks.length; i += 2) chunk(out, (String) chunks[i], (byte[]) chunks[i + 1]);
        return out.toByteArray();
    }

    /** A PNG header chunk's data: size, bit depth, colour type, no interlace. */
    public static byte[] ihdr(int width, int height, int bitDepth, int colorType) {
        var out = new ByteArrayOutputStream();
        int32(out, width);
        int32(out, height);
        out.write(bitDepth);
        out.write(colorType);
        out.write(0);
        out.write(0);
        out.write(0);
        return out.toByteArray();
    }

    /** {@code rows} rows of {@code row} -- its filter byte first -- deflated: a PNG's image data. */
    public static byte[] idat(byte[] row, int rows) {
        var deflater = new java.util.zip.Deflater(9);
        var out = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        for (int y = 0; y < rows; y++) {
            deflater.setInput(row);
            while (!deflater.needsInput()) out.write(buf, 0, deflater.deflate(buf));
        }
        deflater.finish();
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf));
        return out.toByteArray();
    }

    /** A grey PNG with alpha (colour type 4), every pixel (grey, alpha). */
    public static byte[] greyAlphaPng(int width, int height, int grey, int alpha) {
        byte[] row = new byte[1 + 2 * width];
        for (int x = 0; x < width; x++) { row[1 + 2 * x] = (byte) grey; row[2 + 2 * x] = (byte) alpha; }
        return pngOf("IHDR", ihdr(width, height, 8, 4), "IDAT", idat(row, height), "IEND", new byte[0]);
    }

    /** A grey PNG (colour type 0) whose left half is grey value 0, made transparent by tRNS, its right half {@code grey}. */
    public static byte[] greyTrnsPng(int width, int height, int grey) {
        byte[] row = new byte[1 + width];
        for (int x = width / 2; x < width; x++) row[1 + x] = (byte) grey;
        return pngOf("IHDR", ihdr(width, height, 8, 0), "tRNS", new byte[]{0, 0}, "IDAT", idat(row, height),
                "IEND", new byte[0]);
    }

    /** A uniform grey PNG declaring {@code width} x {@code height}, small on disk however large: its rows deflate to nothing. */
    public static byte[] uniformGreyPng(int width, int height) {
        byte[] row = new byte[1 + width];
        java.util.Arrays.fill(row, (byte) 128);
        row[0] = 0;
        return pngOf("IHDR", ihdr(width, height, 8, 0), "IDAT", idat(row, height), "IEND", new byte[0]);
    }

    /**
     * A JPEG of this frame marker -- 0xC0 baseline, 0xC2 progressive, 0xC9 arithmetic -- declaring
     * {@code width} x {@code height} in {@code components} components, in {@code scans} scans, with a
     * few bytes of coded data each: its tables valid, its pixels mostly absent. Small however large it says it is.
     */
    public static byte[] jpegDeclaring(int frame, int width, int height, int components, int scans) {
        var out = new ByteArrayOutputStream();
        out.write(0xFF); out.write(0xD8);
        // One quantisation table of ones.
        segment(out, 0xDB, concat(new byte[]{0}, filled(64, 1)));
        var sof = new ByteArrayOutputStream();
        sof.write(8);
        sof.write(height >>> 8); sof.write(height);
        sof.write(width >>> 8); sof.write(width);
        sof.write(components);
        for (int c = 1; c <= components; c++) { sof.write(c); sof.write(0x11); sof.write(0); }
        segment(out, frame, sof.toByteArray());
        // A DC and an AC table, each of one code of two bits: category 0, and end of block.
        byte[] counts = new byte[16];
        counts[1] = 1;
        segment(out, 0xC4, concat(new byte[]{0x00}, counts, new byte[]{0}, new byte[]{0x10}, counts, new byte[]{0}));
        for (int s = 0; s < scans; s++) {
            var sos = new ByteArrayOutputStream();
            int inScan = scans == 1 ? components : 1;
            sos.write(inScan);
            for (int c = 1; c <= inScan; c++) { sos.write(scans == 1 ? c : s + 1); sos.write(0x00); }
            sos.write(0); sos.write(63); sos.write(0);
            segment(out, 0xDA, sos.toByteArray());
            out.writeBytes(new byte[64]);
        }
        out.write(0xFF); out.write(0xD9);
        return out.toByteArray();
    }

    /** A GIF declaring {@code width} x {@code height}, of two colours, with a few codes of data. */
    public static byte[] gifDeclaring(int width, int height) {
        var out = new ByteArrayOutputStream();
        out.writeBytes("GIF89a".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        out.write(width); out.write(width >>> 8); out.write(height); out.write(height >>> 8);
        out.write(0x80); out.write(0); out.write(0);
        out.writeBytes(new byte[]{0, 0, 0, (byte) 255, (byte) 255, (byte) 255});
        out.write(0x2C);
        out.writeBytes(new byte[]{0, 0, 0, 0});
        out.write(width); out.write(width >>> 8); out.write(height); out.write(height >>> 8);
        out.write(0);
        out.write(2);
        out.write(4); out.writeBytes(new byte[]{(byte) 0x84, 0x1D, (byte) 0x81, 0x7A});
        out.write(0);
        out.write(0x3B);
        return out.toByteArray();
    }

    /** {@code jpeg} with a segment of {@code marker} right after its start. */
    public static byte[] withSegment(byte[] jpeg, int marker, byte[] data) {
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        segment(out, marker, data);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    /** An APP1 "Exif" segment's data whose TIFF says only Orientation = {@code orientation}, and then {@code more}. */
    public static byte[] exif(int orientation, String more) {
        byte[] tiff = {'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, (byte) orientation, 0, 0, 0, 0, 0, 0, 0};
        return concat("Exif\0\0".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), tiff,
                more.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    /** {@code png} with a chunk of {@code type} after its header chunk. */
    public static byte[] withChunk(byte[] png, String type, byte[] data) {
        var out = new ByteArrayOutputStream();
        out.write(png, 0, 33);
        chunk(out, type, data);
        out.write(png, 33, png.length - 33);
        return out.toByteArray();
    }

    static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        int32(out, data.length);
        byte[] t = type.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        out.writeBytes(t);
        out.writeBytes(data);
        var crc = new java.util.zip.CRC32();
        crc.update(t);
        crc.update(data);
        int32(out, (int) crc.getValue());
    }

    static void segment(ByteArrayOutputStream out, int marker, byte[] data) {
        out.write(0xFF);
        out.write(marker);
        out.write((data.length + 2) >>> 8);
        out.write(data.length + 2);
        out.writeBytes(data);
    }

    static void int32(ByteArrayOutputStream out, int v) {
        out.write(v >>> 24); out.write(v >>> 16); out.write(v >>> 8); out.write(v);
    }

    static byte[] filled(int n, int value) {
        byte[] b = new byte[n];
        java.util.Arrays.fill(b, (byte) value);
        return b;
    }

    static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }
}
