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
            var h = CloudImageCopy.header(Files.write(dir.resolve("f" + frame), TestImages.jpegDeclaring(frame, 30000, 30000, 3, 1)));
            assertNotNull(h.refused(), "frame " + Integer.toHexString(frame));
        }
        var several = CloudImageCopy.header(Files.write(dir.resolve("s"), TestImages.jpegDeclaring(0xC0, 30000, 30000, 3, 3)));
        assertTrue(several.refused().contains("several scans"), several.refused());
        var baseline = CloudImageCopy.header(Files.write(dir.resolve("b"), TestImages.jpegDeclaring(0xC0, 30000, 30000, 3, 1)));
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
    @DisplayName("the orientation is read from the TIFF that EXIF is, either byte order; anything unreadable is upright")
    void orientation() {
        assertEquals(6, CloudImageCopy.orientation(TestImages.exif(6, ""), 6));
        byte[] motorola = {'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 8, 0, 0, 0, 0, 0, 0};
        assertEquals(8, CloudImageCopy.orientation(motorola, 0));
        assertEquals(1, CloudImageCopy.orientation(new byte[]{'I', 'I', 42, 0, 99, 0, 0, 0}, 0), "an IFD past the end");
        assertEquals(1, CloudImageCopy.orientation(new byte[]{'X', 'X'}, 0));
    }
}
