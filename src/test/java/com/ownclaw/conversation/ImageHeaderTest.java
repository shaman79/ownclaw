package com.ownclaw.conversation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a pasted file's structure says before any pixel of it is read: the files whose reading takes
 * memory in proportion to their whole declared size are refused there, from a few hundred bytes,
 * and never decoded -- so nothing here decodes a large image either.
 */
class ImageHeaderTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    @DisplayName("a progressive, lossless, hierarchical or arithmetic JPEG, and a baseline one in several scans, are refused from the header")
    void jpegsReadWhole(@TempDir Path dir) throws Exception {
        for (int frame : new int[]{0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF}) {
            var h = CloudImageCopy.header(Files.write(dir.resolve("f" + frame), TestImages.jpegDeclaring(frame, 16000, 16000, 3, 1)));
            assertNotNull(h.refused(), "frame " + Integer.toHexString(frame));
        }
        var several = CloudImageCopy.header(Files.write(dir.resolve("s"), TestImages.jpegDeclaring(0xC0, 16000, 16000, 3, 3)));
        assertTrue(several.refused().contains("several scans"), several.refused());
        var baseline = CloudImageCopy.header(Files.write(dir.resolve("b"), TestImages.jpegDeclaring(0xC0, 16000, 16000, 3, 1)));
        assertNull(baseline.refused(), "baseline in one scan is read row by row: " + baseline.refused());
        assertEquals(CloudImageCopy.Format.JPEG, baseline.format());
        var extended = CloudImageCopy.header(Files.write(dir.resolve("e"), TestImages.jpegDeclaring(0xC1, 3000, 3000, 1, 1)));
        assertNull(extended.refused());
        // Mutation: accept SOF2 -> a 200-byte file asks libjpeg for gigabytes; accept several scans -> the same.
    }

    @Test
    @DisplayName("a PNG wider than a reader can hold a few rows of is refused from its header; one of an ordinary width is not")
    void rowsTooWide(@TempDir Path dir) throws Exception {
        // 16-bit RGBA, three million pixels wide: 24 MB a row.
        byte[] row = new byte[1 + 8 * 3_000_000];
        byte[] wide = TestImages.pngOf("IHDR", TestImages.ihdr(3_000_000, 1, 16, 6), "IDAT", TestImages.idat(row, 1),
                "IEND", new byte[0]);
        var made = CloudImageCopy.of(Files.write(dir.resolve("wide.png"), wide));
        assertNull(made.copy());
        assertTrue(made.privateBecause().contains("wider than this server can read a row of"), made.privateBecause());
        assertEquals(8, CloudImageCopy.header(dir.resolve("wide.png")).bytesPerPixel());
        assertNotNull(CloudImageCopy.of(Files.write(dir.resolve("ok.png"), TestImages.uniformGreyPng(4000, 10))).copy());
        // Mutation: drop the row check -> the reader is handed rows of any width.
    }

    @Test
    @DisplayName("a declaration no screenshot or photo has -- over 268 million pixels, over 10,000 parts beside the picture's data -- is refused from the header, at once")
    void tooLargeToCheck(@TempDir Path dir) throws Exception {
        var tooMany = new java.util.LinkedHashMap<String, byte[]>();
        tooMany.put("png 16385 x 16385", TestImages.pngOf("IHDR", TestImages.ihdr(16385, 16385, 8, 0),
                "IDAT", TestImages.idat(new byte[]{0, 0}, 1), "IEND", new byte[0]));
        // The reviewers' 215 bytes, a quarter of a minute of decoding.
        tooMany.put("jpeg 46340 x 46340", TestImages.jpegDeclaring(0xC0, 46340, 46340, 3, 1));
        tooMany.put("gif 65535 x 65535", TestImages.gifDeclaring(65535, 65535));
        for (var e : tooMany.entrySet()) {
            long start = System.nanoTime();
            var made = CloudImageCopy.of(Files.write(dir.resolve("p" + e.getValue().length), e.getValue()));
            assertNull(made.copy(), e.getKey());
            assertEquals(CloudImageCopy.TOO_MANY_PIXELS, made.privateBecause(), e.getKey());
            assertTrue(System.nanoTime() - start < 500_000_000L, e.getKey() + " answered from its header");
        }
        assertNull(CloudImageCopy.header(Files.write(dir.resolve("edge.png"), TestImages.pngOf("IHDR",
                TestImages.ihdr(16384, 16384, 8, 0), "IDAT", TestImages.idat(new byte[]{0, 0}, 1), "IEND", new byte[0])))
                .refused(), "2^28 pixels exactly is checked");

        var parts = new java.util.LinkedHashMap<String, byte[]>();
        parts.put("png of empty chunks", pngWithEmptyChunks(MAX_PARTS + 1));
        parts.put("png of tiny IDATs", pngOfTinyIdats(MAX_PARTS + 2));
        parts.put("jpeg of comments", jpegWithComments(MAX_PARTS + 1));
        parts.put("gif comment in one-byte sub-blocks", gifWithComment(MAX_PARTS + 1));
        parts.put("gif picture in one-byte sub-blocks", gifOfTinyBlocks(MAX_PARTS + 2));
        for (var e : parts.entrySet()) {
            var made = CloudImageCopy.of(Files.write(dir.resolve("q" + e.getKey().hashCode()), e.getValue()));
            assertEquals(CloudImageCopy.TOO_MANY_PARTS, made.privateBecause(), e.getKey());
        }
        // Under the bound: read, and what it carries said.
        assertEquals("data of another program", CloudImageCopy.header(
                Files.write(dir.resolve("ok.png"), pngWithEmptyChunks(9_000))).metadata());
        assertEquals("a comment", CloudImageCopy.header(Files.write(dir.resolve("ok.gif"), gifWithComment(9_000))).metadata());
        assertEquals("a comment", CloudImageCopy.header(Files.write(dir.resolve("ok.jpg"), jpegWithComments(9_000))).metadata());
        assertNull(CloudImageCopy.header(Files.write(dir.resolve("ok2.gif"), gifOfTinyBlocks(9_000))).refused());
        // Mutation: no pixel bound -> the JPEG is decoded for seconds; no part count -> each is read part by part.
    }

    @Test
    @DisplayName("a PNG's header is its first chunk and its only one: a second, or one after another chunk, is refused -- the reader decodes by the first")
    void aSecondIhdrIsRefused(@TempDir Path dir) throws Exception {
        // The review's file: a first header of 22,000,000 x 1 16-bit RGBA -- 176 MB a row -- and a
        // second of one 1-bit grey pixel, which the walk took the rows' size from.
        byte[] row = new byte[1 + 8 * 22_000_000];
        byte[] two = TestImages.pngOf("IHDR", TestImages.ihdr(22_000_000, 1, 16, 6), "IHDR", TestImages.ihdr(1, 1, 1, 0),
                "IDAT", TestImages.idat(row, 1), "IEND", new byte[0]);
        row = null;
        var made = CloudImageCopy.of(Files.write(dir.resolve("two.png"), two));
        assertNull(made.copy());
        assertTrue(made.privateBecause().contains("its header is not its first part, or not its only one"), made.privateBecause());
        // A header after another chunk is no header the reader reads first either.
        byte[] late = TestImages.pngOf("tEXt", "a\0b".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),
                "IHDR", TestImages.ihdr(10, 10, 8, 0), "IDAT", TestImages.idat(new byte[11], 10), "IEND", new byte[0]);
        assertNotNull(CloudImageCopy.header(Files.write(dir.resolve("late.png"), late)).refused());
        // One header, first: read.
        assertNull(CloudImageCopy.header(Files.write(dir.resolve("one.png"), TestImages.png(40, 30))).refused());
        // Mutation: let any chunk be the header, the last one deciding -> 504 MB for a 171 KB file.
    }

    static final int MAX_PARTS = CloudImageCopy.MAX_PARTS;

    /** A 1 x 1 grey PNG with {@code n} empty chunks of a program's own before its data. */
    static byte[] pngWithEmptyChunks(int n) {
        var out = new java.io.ByteArrayOutputStream();
        byte[] head = TestImages.pngOf("IHDR", TestImages.ihdr(1, 1, 8, 0));
        out.writeBytes(head);
        for (int i = 0; i < n; i++) TestImages.chunk(out, "prVt", new byte[0]);
        byte[] rest = TestImages.pngOf("IDAT", TestImages.idat(new byte[]{0, (byte) 128}, 1), "IEND", new byte[0]);
        out.write(rest, 8, rest.length - 8);
        return out.toByteArray();
    }

    /** A 1 x 1 grey PNG whose compressed data is split into {@code n} IDAT chunks of a byte or so. */
    static byte[] pngOfTinyIdats(int n) {
        byte[] data = TestImages.idat(new byte[]{0, (byte) 128}, 1);
        var out = new java.io.ByteArrayOutputStream();
        out.writeBytes(TestImages.pngOf("IHDR", TestImages.ihdr(1, 1, 8, 0)));
        for (int i = 0; i < n; i++) {
            int from = Math.min(i, data.length), to = i == n - 1 ? data.length : Math.min(i + 1, data.length);
            TestImages.chunk(out, "IDAT", java.util.Arrays.copyOfRange(data, from, to));
        }
        TestImages.chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    /** A small JPEG with {@code n} empty comments after its start. */
    static byte[] jpegWithComments(int n) throws Exception {
        byte[] jpeg = TestImages.encoded(TestImages.screenshot(40, 30), "jpeg");
        var out = new java.io.ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        for (int i = 0; i < n; i++) out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xFE, 0, 2});
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    /** A 1 x 1 GIF behind a comment of {@code n} one-byte sub-blocks. */
    static byte[] gifWithComment(int n) {
        var out = new java.io.ByteArrayOutputStream();
        out.writeBytes("GIF89a".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        out.writeBytes(new byte[]{1, 0, 1, 0, (byte) 0x80, 0, 0, 0, 0, 0, (byte) 255, (byte) 255, (byte) 255});
        out.write(0x21);
        out.write(0xFE);
        for (int i = 0; i < n; i++) {
            out.write(1);
            out.write('x');
        }
        out.write(0);
        out.writeBytes(new byte[]{0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 0x01, 0, 0x3B});
        return out.toByteArray();
    }

    /** A 1 x 1 GIF whose picture's data is in {@code n} sub-blocks of one byte. */
    static byte[] gifOfTinyBlocks(int n) {
        var out = new java.io.ByteArrayOutputStream();
        out.writeBytes("GIF89a".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        out.writeBytes(new byte[]{1, 0, 1, 0, (byte) 0x80, 0, 0, 0, 0, 0, (byte) 255, (byte) 255, (byte) 255});
        out.writeBytes(new byte[]{0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2});
        byte[] lzw = {0x44, 0x01};
        for (int i = 0; i < n; i++) {
            out.write(1);
            out.write(i < lzw.length ? lzw[i] : 0);
        }
        out.write(0);
        out.write(0x3B);
        return out.toByteArray();
    }

    @Test
    @DisplayName("the orientation is read from the TIFF that EXIF is, either byte order; anything unreadable is upright")
    void orientation() {
        assertEquals(6, CloudImageCopy.orientation(TestImages.exif(6, ""), 6));
        byte[] motorola = {'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 8, 0, 0, 0, 0, 0, 0};
        assertEquals(8, CloudImageCopy.orientation(motorola, 0));
        assertEquals(1, CloudImageCopy.orientation(new byte[]{'I', 'I', 42, 0, 99, 0, 0, 0}, 0), "an IFD past the end");
        assertEquals(1, CloudImageCopy.orientation(new byte[]{'X', 'X'}, 0));
    }
}
