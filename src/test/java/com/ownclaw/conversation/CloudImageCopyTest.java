package com.ownclaw.conversation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The copy of a pasted image the cloud model is sent: Anthropic's own resize rule, at and around
 * its limits; the pasted bytes themselves only when they hold the picture and nothing else; a PNG,
 * or a JPEG when a PNG would be larger than one image may be; the same bytes every time; and no
 * copy at all of anything that is not a PNG, JPEG or GIF this code decoded.
 */
class CloudImageCopyTest {

    static {
        // Before anything here touches AWT: the suite runs where no display is.
        System.setProperty("java.awt.headless", "true");
    }

    static BufferedImage screenshot(int width, int height) {
        return TestImages.screenshot(width, height);
    }

    static BufferedImage noise(int width, int height, long seed) {
        return TestImages.noise(width, height, seed);
    }

    static byte[] encoded(BufferedImage image, String format) throws Exception {
        return TestImages.encoded(image, format);
    }

    static Path file(Path dir, String name, byte[] bytes) throws Exception {
        return Files.write(dir.resolve(name), bytes);
    }

    private static BufferedImage decoded(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    @Test
    @DisplayName("the size is Anthropic's: its documented examples, both tiers, and the edges at and past the limits")
    void theApisOwnRule() {
        // The examples platform.claude.com gives, standard tier and high-resolution tier.
        assertEquals(new CloudImageCopy.Size(924, 1307), CloudImageCopy.resizedSize(1075, 1520, 1568, 1568));
        assertEquals(new CloudImageCopy.Size(1456, 819), CloudImageCopy.resizedSize(1920, 1080, 1568, 1568));
        assertEquals(new CloudImageCopy.Size(2576, 1449), CloudImageCopy.resizedSize(3840, 2160, 2576, 4784));
        assertEquals(new CloudImageCopy.Size(2000, 1500), CloudImageCopy.resizedSize(2000, 1500, 2576, 4784),
                "3,888 tokens: not resized on the high-resolution tier");
        assertEquals(3_888, CloudImageCopy.countImageTokens(2000, 1500));
        // At the limits: 2,576 is 92 patches exactly, and 92 x 52 is 4,784 tokens exactly.
        assertEquals(4_784, CloudImageCopy.countImageTokens(2576, 1449));
        assertEquals(new CloudImageCopy.Size(2576, 1449), CloudImageCopy.resizedSize(2576, 1449, 2576, 4784));
        assertEquals(new CloudImageCopy.Size(1449, 2576), CloudImageCopy.resizedSize(1449, 2576, 2576, 4784),
                "a tall image, the same rule turned");
        // A pixel past either limit is resized.
        assertNotEquals(new CloudImageCopy.Size(2577, 1449), CloudImageCopy.resizedSize(2577, 1449, 2576, 4784));
        var wide = CloudImageCopy.resizedSize(2577, 100, 2576, 4784);
        assertTrue(Math.ceilDiv(wide.width(), 28) * 28 <= 2576, "the padded edge fits: " + wide);
        var square = CloudImageCopy.resizedSize(2576, 2576, 2576, 4784);
        assertTrue(CloudImageCopy.countImageTokens(square.width(), square.height()) <= 4784, square.toString());
        assertTrue(CloudImageCopy.countImageTokens(square.width() + 28, square.height() + 28) > 4784,
                "the largest that fits: " + square);
        // Mutation: Math.round for Math.rint, or the unpadded edge -> one of these sizes differs.
    }

    @Test
    @DisplayName("a screenshot within the tier is sent as pasted: the file's own bytes, and the same pixel size")
    void withinTheTierTheOriginalIsTheCopy(@TempDir Path dir) throws Exception {
        byte[] png = encoded(screenshot(1920, 1080), "png");
        var made = CloudImageCopy.of(file(dir, "a.png", png));
        var copy = made.copy();
        assertNotNull(copy, made.privateBecause());
        assertTrue(copy.original());
        assertArrayEquals(png, copy.bytes(), "the bytes as pasted");
        assertEquals("image/png", copy.mediaType());
        assertEquals(1920, copy.width());
        assertEquals(1080, copy.height());
        assertEquals(1920, copy.originalWidth());
        assertEquals(CloudImageCopy.sha256(png), copy.sha256());
    }

    @Test
    @DisplayName("larger than the tier: a PNG of the largest size within both limits, aspect kept, the same bytes each time")
    void largerIsScaledOnceAndTheSameEachTime(@TempDir Path dir) throws Exception {
        Path f = file(dir, "big.png", encoded(screenshot(3840, 2160), "png"));
        var one = CloudImageCopy.of(f).copy();
        var two = CloudImageCopy.of(f).copy();
        assertFalse(one.original());
        assertEquals("image/png", one.mediaType());
        assertEquals(2576, one.width());
        assertEquals(1449, one.height());
        assertEquals(3840, one.originalWidth());
        assertEquals(2160, one.originalHeight());
        var pixels = decoded(one.bytes());
        assertEquals(2576, pixels.getWidth(), "the bytes are the size the copy says");
        assertEquals(1449, pixels.getHeight());
        assertArrayEquals(one.bytes(), two.bytes(), "made twice, byte for byte the same");
        // A strip far past the edge limit, its short edge rounded as the API rounds it.
        var strip = CloudImageCopy.of(file(dir, "strip.png", encoded(screenshot(20000, 300), "png"))).copy();
        assertEquals(new CloudImageCopy.Size(2576, 39), new CloudImageCopy.Size(strip.width(), strip.height()));
        // Mutation: drop the scale -> 3840 wide; drop the halving loop's last step -> not 2576.
    }

    @Test
    @DisplayName("a PNG larger than one image may be, base64: the copy is a JPEG of high quality instead, the same bytes each time")
    void tooLargeAsPngIsAJpeg(@TempDir Path dir) throws Exception {
        // Noise at the tier's own size: its PNG is over 7.5 MB, 10 MB as base64.
        Path f = file(dir, "noise.png", encoded(noise(2576, 1449, 7), "png"));
        assertTrue(CloudImageCopy.base64(Files.size(f)) > CloudImageCopy.MAX_BASE64, "the original is too large");
        var copy = CloudImageCopy.of(f).copy();
        assertNotNull(copy);
        assertEquals("image/jpeg", copy.mediaType());
        assertFalse(copy.original());
        assertEquals(2576, copy.width());
        assertTrue(CloudImageCopy.base64(copy.bytes().length) <= CloudImageCopy.MAX_BASE64);
        assertEquals((byte) 0xFF, copy.bytes()[0]);
        assertEquals((byte) 0xD8, copy.bytes()[1]);
        assertArrayEquals(copy.bytes(), CloudImageCopy.of(f).copy().bytes(), "made twice, the same");
        // Mutation: keep the PNG regardless -> the API refuses the request for its size.
    }

    @Test
    @DisplayName("only the picture leaves: a text chunk, bytes after the end, EXIF -- the copy is made from the pixels, without them")
    void metadataNeverLeaves(@TempDir Path dir) throws Exception {
        byte[] png = encoded(screenshot(800, 600), "png");
        byte[] withText = withChunk(png, "tEXt", "Comment\0account 1234567890 of the owner");
        var text = CloudImageCopy.of(file(dir, "t.png", withText)).copy();
        assertFalse(text.original(), "a text chunk is not the picture");
        assertFalse(new String(text.bytes(), StandardCharsets.ISO_8859_1).contains("1234567890"));
        assertTrue(CloudImageCopy.onlyThePicturePng(text.bytes()), "the copy has only the picture's chunks");
        assertEquals(800, text.width(), "not scaled: it fits the tier");

        byte[] trailing = Arrays.copyOf(png, png.length + 20);
        System.arraycopy("SECRET-AFTER-THE-END".getBytes(StandardCharsets.ISO_8859_1), 0, trailing, png.length, 20);
        var after = CloudImageCopy.of(file(dir, "after.png", trailing)).copy();
        assertFalse(after.original());
        assertFalse(new String(after.bytes(), StandardCharsets.ISO_8859_1).contains("SECRET"));

        byte[] jpeg = encoded(screenshot(800, 600), "jpeg");
        assertTrue(CloudImageCopy.onlyThePictureJpeg(jpeg), "ImageIO's own JPEG holds the picture alone");
        assertTrue(CloudImageCopy.of(file(dir, "j.jpg", jpeg)).copy().original());
        byte[] exif = withSegment(jpeg, 0xE1, "Exif\0\0GPS 49.0N 16.0E serial 0000");
        var gps = CloudImageCopy.of(file(dir, "exif.jpg", exif)).copy();
        assertFalse(gps.original());
        assertEquals("image/png", gps.mediaType(), "a lossless copy of the decoded pixels");
        assertFalse(new String(gps.bytes(), StandardCharsets.ISO_8859_1).contains("GPS"));
        // Mutation: accept any chunk or segment -> the account number or the location leaves.
    }

    @Test
    @DisplayName("a GIF's copy is its first frame as a PNG: what the model reads of it")
    void aGifIsItsFirstFrame(@TempDir Path dir) throws Exception {
        var copy = CloudImageCopy.of(file(dir, "g.gif", encoded(screenshot(300, 200), "gif"))).copy();
        assertNotNull(copy);
        assertEquals("image/png", copy.mediaType());
        assertFalse(copy.original());
        assertEquals(300, decoded(copy.bytes()).getWidth());
    }

    @Test
    @DisplayName("what is not a PNG, JPEG or GIF that decodes stays private: text, a WebP, a WBMP-shaped run of bytes, a broken PNG")
    void nothingElseIsAnImage(@TempDir Path dir) throws Exception {
        for (var bad : new byte[][]{
                "date,amount\n2026-09-01,-1200\n".getBytes(StandardCharsets.UTF_8),
                "RIFF\0\0\0\0WEBPVP8 \0\0\0\0".getBytes(StandardCharsets.ISO_8859_1),
                {0, 0, 10, 10, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16},
                Arrays.copyOf(encoded(screenshot(400, 300), "png"), 60)}) {
            var made = CloudImageCopy.of(file(dir, "x" + bad.length, bad));
            assertNull(made.copy(), "no copy of " + new String(bad, 0, Math.min(12, bad.length), StandardCharsets.ISO_8859_1));
            assertNotNull(made.privateBecause());
        }
        assertTrue(CloudImageCopy.of(file(dir, "w.webp", "RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1)))
                .privateBecause().contains("WebP"));
        // Mutation: let ImageIO pick the reader by its guess -> the WBMP-shaped bytes "decode".
    }

    /** {@code png} with a chunk of {@code type} after its header chunk. */
    static byte[] withChunk(byte[] png, String type, String data) {
        byte[] t = type.getBytes(StandardCharsets.ISO_8859_1);
        byte[] d = data.getBytes(StandardCharsets.ISO_8859_1);
        var crc = new CRC32();
        crc.update(t);
        crc.update(d);
        var out = new ByteArrayOutputStream();
        out.write(png, 0, 33);  // signature and IHDR
        out.write(d.length >>> 24); out.write(d.length >>> 16); out.write(d.length >>> 8); out.write(d.length);
        out.writeBytes(t);
        out.writeBytes(d);
        long c = crc.getValue();
        out.write((int) (c >>> 24)); out.write((int) (c >>> 16)); out.write((int) (c >>> 8)); out.write((int) c);
        out.write(png, 33, png.length - 33);
        return out.toByteArray();
    }

    /** {@code jpeg} with a segment of {@code marker} after its start. */
    static byte[] withSegment(byte[] jpeg, int marker, String data) {
        byte[] d = data.getBytes(StandardCharsets.ISO_8859_1);
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(marker);
        out.write((d.length + 2) >>> 8);
        out.write(d.length + 2);
        out.writeBytes(d);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }
}
