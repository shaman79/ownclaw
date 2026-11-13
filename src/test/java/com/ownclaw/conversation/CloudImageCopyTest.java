package com.ownclaw.conversation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The copy of a pasted image the cloud model is sent: Anthropic's own resize rule, at and around
 * its limits; always the decoded pixels encoded again, in the original's family -- a PNG for a PNG
 * or a GIF, a JPEG for a JPEG -- turned upright as its EXIF says, grey kept grey; the same bytes
 * every time; what the file carries beside the picture said; and no copy at all of anything that is
 * not a PNG, a baseline JPEG or a GIF this code decoded within its time.
 */
class CloudImageCopyTest {

    static {
        // Before anything here touches AWT: the suite runs where no display is.
        System.setProperty("java.awt.headless", "true");
    }

    static BufferedImage screenshot(int width, int height) {
        return TestImages.screenshot(width, height);
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

    private static String latin1(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    @Test
    @DisplayName("the size is Anthropic's: its documented examples, both tiers, and the edges at and past the limits")
    void theApisOwnRule() {
        assertEquals(new CloudImageCopy.Size(924, 1307), CloudImageCopy.resizedSize(1075, 1520, 1568, 1568));
        assertEquals(new CloudImageCopy.Size(1456, 819), CloudImageCopy.resizedSize(1920, 1080, 1568, 1568));
        assertEquals(new CloudImageCopy.Size(2576, 1449), CloudImageCopy.resizedSize(3840, 2160, 2576, 4784));
        assertEquals(new CloudImageCopy.Size(2000, 1500), CloudImageCopy.resizedSize(2000, 1500, 2576, 4784),
                "3,888 tokens: not resized on the high-resolution tier");
        assertEquals(3_888, CloudImageCopy.countImageTokens(2000, 1500));
        assertEquals(4_784, CloudImageCopy.countImageTokens(2576, 1449));
        assertEquals(new CloudImageCopy.Size(2576, 1449), CloudImageCopy.resizedSize(2576, 1449, 2576, 4784));
        assertEquals(new CloudImageCopy.Size(1449, 2576), CloudImageCopy.resizedSize(1449, 2576, 2576, 4784));
        assertNotEquals(new CloudImageCopy.Size(2577, 1449), CloudImageCopy.resizedSize(2577, 1449, 2576, 4784));
        var square = CloudImageCopy.resizedSize(2576, 2576, 2576, 4784);
        assertTrue(CloudImageCopy.countImageTokens(square.width(), square.height()) <= 4784, square.toString());
        assertTrue(CloudImageCopy.countImageTokens(square.width() + 28, square.height() + 28) > 4784, square.toString());
        // Mutation: Math.round for Math.rint, or the unpadded edge -> one of these sizes differs.
    }

    @Test
    @DisplayName("within the tier a screenshot keeps its size, as a PNG encoded again from its pixels -- never the pasted bytes")
    void withinTheTierItIsEncodedAgain(@TempDir Path dir) throws Exception {
        // A PNG another encoder wrote: its rows unfiltered, deflated hard.
        BufferedImage picture = screenshot(640, 480);
        byte[] row = new byte[1 + 3 * 640];
        byte[] handMade = TestImages.pngOf("IHDR", TestImages.ihdr(640, 480, 8, 2), "IDAT", TestImages.idat(row, 480),
                "IEND", new byte[0]);
        var copy = CloudImageCopy.of(file(dir, "hand.png", handMade)).copy();
        assertNotNull(copy);
        assertEquals("image/png", copy.mediaType());
        assertEquals(640, copy.width());
        assertFalse(Arrays.equals(handMade, copy.bytes()), "made again, not sent as pasted");
        assertNull(copy.metadata(), "nothing beside the picture");
        assertEquals(640, decoded(copy.bytes()).getWidth());
        var shot = CloudImageCopy.of(file(dir, "a.png", encoded(picture, "png"))).copy();
        assertEquals(picture.getRGB(320, 240), decoded(shot.bytes()).getRGB(320, 240), "the same pixels");
        // Mutation: hand back the file's own bytes within the tier -> the hand-made bytes go as they are.
    }

    @Test
    @DisplayName("larger than the tier: a PNG of the largest size within both limits, aspect kept, the same bytes each time")
    void largerIsScaledOnceAndTheSameEachTime(@TempDir Path dir) throws Exception {
        Path f = file(dir, "big.png", encoded(screenshot(3840, 2160), "png"));
        var one = CloudImageCopy.of(f).copy();
        var two = CloudImageCopy.of(f).copy();
        assertEquals("image/png", one.mediaType());
        assertEquals(2576, one.width());
        assertEquals(1449, one.height());
        assertEquals(3840, one.originalWidth());
        assertEquals(2160, one.originalHeight());
        var pixels = decoded(one.bytes());
        assertEquals(2576, pixels.getWidth());
        assertEquals(1449, pixels.getHeight());
        assertArrayEquals(one.bytes(), two.bytes(), "made twice, byte for byte the same");
        var strip = CloudImageCopy.of(file(dir, "strip.png", encoded(screenshot(20000, 300), "png"))).copy();
        assertEquals(new CloudImageCopy.Size(2576, 39), new CloudImageCopy.Size(strip.width(), strip.height()));
    }

    @Test
    @DisplayName("a JPEG stays a JPEG -- a phone photo is not made a PNG many times its size; a PNG becomes a JPEG only when too large as one")
    void theOriginalsFamily(@TempDir Path dir) throws Exception {
        BufferedImage photo = new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 1200; y++) for (int x = 0; x < 1600; x++) photo.setRGB(x, y, (x * 255 / 1600) << 16 | (y * 255 / 1200) << 8 | 90);
        byte[] jpeg = encoded(photo, "jpeg");
        var copy = CloudImageCopy.of(file(dir, "photo.jpg", jpeg)).copy();
        assertEquals("image/jpeg", copy.mediaType(), "a photograph stays a JPEG");
        assertTrue(copy.bytes().length < 3 * jpeg.length, copy.bytes().length + " bytes from " + jpeg.length);

        // Noise at the tier's own size: as a PNG over 7.5 MB, 10 MB as base64.
        Path noise = file(dir, "noise.png", encoded(TestImages.noise(2576, 1449, 7), "png"));
        var big = CloudImageCopy.of(noise).copy();
        assertEquals("image/jpeg", big.mediaType());
        assertTrue(CloudImageCopy.base64(big.bytes().length) <= CloudImageCopy.MAX_BASE64);
        assertArrayEquals(big.bytes(), CloudImageCopy.of(noise).copy().bytes(), "made twice, the same");
        // Mutation: encode every copy as a PNG first -> the photo's copy is several times its size.
    }

    @Test
    @DisplayName("a phone's portrait photo is turned upright as its EXIF says, its sizes as a viewer shows them")
    void exifOrientationIsApplied(@TempDir Path dir) throws Exception {
        // Stored 800x600, its left half red; EXIF Orientation 6: shown turned a quarter clockwise.
        var stored = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = stored.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 800, 600);
        g.setColor(Color.RED);
        g.fillRect(0, 0, 400, 600);
        g.dispose();
        byte[] jpeg = TestImages.withSegment(encoded(stored, "jpeg"), 0xE1, TestImages.exif(6, ""));
        var copy = CloudImageCopy.of(file(dir, "portrait.jpg", jpeg)).copy();
        assertNotNull(copy);
        assertEquals(600, copy.width(), "upright: as every viewer, and the API, shows it");
        assertEquals(800, copy.height());
        assertEquals(600, copy.originalWidth());
        BufferedImage shown = decoded(copy.bytes());
        assertEquals(600, shown.getWidth());
        assertEquals(Color.RED.getRGB() & 0xF0F0F0, shown.getRGB(300, 100) & 0xF0F0F0, "its top half red");
        assertEquals(Color.WHITE.getRGB() & 0xF0F0F0, shown.getRGB(300, 700) & 0xF0F0F0, "its bottom half white");
        assertEquals("EXIF data", copy.metadata());
        // And 3, half round: the red half on the right.
        var half = decoded(CloudImageCopy.of(file(dir, "o3.jpg", TestImages.withSegment(encoded(stored, "jpeg"), 0xE1,
                TestImages.exif(3, "")))).copy().bytes());
        assertEquals(800, half.getWidth());
        assertEquals(Color.RED.getRGB() & 0xF0F0F0, half.getRGB(700, 300) & 0xF0F0F0);
        // Mutation: ignore the orientation -> 800 wide, on its side.
    }

    @Test
    @DisplayName("grey stays the grey it was: with alpha and with tRNS, 128 is 128, not the 188 Java2D's drawing made of it")
    void greyKeepsItsTone(@TempDir Path dir) throws Exception {
        var alpha = CloudImageCopy.of(file(dir, "la.png", TestImages.greyAlphaPng(800, 600, 128, 255))).copy();
        int red = (decoded(alpha.bytes()).getRGB(400, 300) >> 16) & 255;
        assertTrue(Math.abs(red - 128) <= 2, "grey and alpha: " + red);
        var trns = CloudImageCopy.of(file(dir, "trns.png", TestImages.greyTrnsPng(3000, 2000, 128))).copy();
        BufferedImage t = decoded(trns.bytes());
        int right = t.getRGB(3 * t.getWidth() / 4, t.getHeight() / 2);
        assertTrue(Math.abs(((right >> 16) & 255) - 128) <= 2, "grey and tRNS: " + ((right >> 16) & 255));
        assertEquals(0, t.getRGB(t.getWidth() / 4, t.getHeight() / 2) >>> 24, "its transparent half still transparent");
        // Mutation: draw grey with Java2D -> 188.
    }

    @Test
    @DisplayName("what the file carries beside the picture is said, and never leaves: EXIF with a place and an address, text, bytes after the end")
    void metadataIsSaidAndNeverLeaves(@TempDir Path dir) throws Exception {
        byte[] jpeg = encoded(screenshot(800, 600), "jpeg");
        assertNull(CloudImageCopy.of(file(dir, "plain.jpg", jpeg)).copy().metadata(), "ImageIO's JPEG: the picture alone");
        byte[] gps = TestImages.withSegment(jpeg, 0xE1, TestImages.exif(1, "GPS 49.19N 16.61E owner@example.com"));
        var withGps = CloudImageCopy.of(file(dir, "gps.jpg", gps)).copy();
        assertEquals("EXIF data", withGps.metadata());
        assertFalse(latin1(withGps.bytes()).contains("owner@example.com"));
        assertFalse(latin1(withGps.bytes()).contains("GPS"));

        byte[] png = encoded(screenshot(800, 600), "png");
        assertNull(CloudImageCopy.of(file(dir, "clean.png", png)).copy().metadata());
        var text = CloudImageCopy.of(file(dir, "t.png", TestImages.withChunk(png, "tEXt",
                "Comment\0account 1234567890 of the owner".getBytes(StandardCharsets.ISO_8859_1)))).copy();
        assertEquals("text", text.metadata());
        assertFalse(latin1(text.bytes()).contains("1234567890"));
        // A setting of the wrong length carries whatever it holds.
        var gamma = CloudImageCopy.of(file(dir, "g.png", TestImages.withChunk(png, "gAMA",
                "note: the owner's password hint".getBytes(StandardCharsets.ISO_8859_1)))).copy();
        assertEquals("data where a setting of the picture belongs", gamma.metadata());
        assertFalse(latin1(gamma.bytes()).contains("password"));

        byte[] trailing = Arrays.copyOf(png, png.length + 20);
        System.arraycopy("SECRET-AFTER-THE-END".getBytes(StandardCharsets.ISO_8859_1), 0, trailing, png.length, 20);
        var after = CloudImageCopy.of(file(dir, "after.png", trailing)).copy();
        assertEquals("bytes after the picture's end", after.metadata());
        assertFalse(latin1(after.bytes()).contains("SECRET"));
        // Mutation: say nothing of an APP1 -> a skill that reads the photo's EXIF sends it as public.
    }

    @Test
    @DisplayName("a GIF's copy is its first frame as a PNG: what the model reads of it")
    void aGifIsItsFirstFrame(@TempDir Path dir) throws Exception {
        var copy = CloudImageCopy.of(file(dir, "g.gif", encoded(screenshot(300, 200), "gif"))).copy();
        assertNotNull(copy);
        assertEquals("image/png", copy.mediaType());
        assertEquals(300, decoded(copy.bytes()).getWidth());
    }

    @Test
    @DisplayName("what is not a PNG, baseline JPEG or GIF stays private, unread: text, a WebP, WBMP-shaped bytes, a broken PNG, progressive and multi-scan JPEGs")
    void nothingElseIsAnImage(@TempDir Path dir) throws Exception {
        for (var bad : new byte[][]{
                "date,amount\n2026-09-01,-1200\n".getBytes(StandardCharsets.UTF_8),
                "RIFF\0\0\0\0WEBPVP8 \0\0\0\0".getBytes(StandardCharsets.ISO_8859_1),
                {0, 0, 10, 10, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16},
                Arrays.copyOf(encoded(screenshot(400, 300), "png"), 60)}) {
            var made = CloudImageCopy.of(file(dir, "x" + bad.length, bad));
            assertNull(made.copy(), "no copy of " + latin1(Arrays.copyOf(bad, Math.min(12, bad.length))));
            assertNotNull(made.privateBecause());
        }
        assertTrue(CloudImageCopy.of(file(dir, "w.webp", "RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1)))
                .privateBecause().contains("WebP"));
        // A 200-byte progressive JPEG declaring 30,000 x 30,000 took a server's memory: not read at all.
        long start = System.nanoTime();
        var progressive = CloudImageCopy.of(file(dir, "p.jpg", TestImages.jpegDeclaring(0xC2, 16000, 16000, 3, 1)));
        assertNull(progressive.copy());
        assertTrue(progressive.privateBecause().contains("progressive"), progressive.privateBecause());
        assertTrue(System.nanoTime() - start < 2_000_000_000L, "refused from its header, without decoding it");
        for (int frame : new int[]{0xC3, 0xC5, 0xC6, 0xC9, 0xCA}) {
            assertNull(CloudImageCopy.of(file(dir, "f" + frame, TestImages.jpegDeclaring(frame, 3000, 3000, 3, 1))).copy(),
                    "frame " + Integer.toHexString(frame));
        }
        var scans = CloudImageCopy.of(file(dir, "s.jpg", TestImages.jpegDeclaring(0xC0, 16000, 16000, 3, 3)));
        assertNull(scans.copy(), "a baseline JPEG in several scans is read into buffers of its whole size");
        assertTrue(scans.privateBecause().contains("several scans"), scans.privateBecause());
        // Mutation: let ImageIO pick the reader by its guess -> the WBMP-shaped bytes "decode";
        // accept SOF2 -> the 30,000 x 30,000 JPEG is decoded into gigabytes.
    }

    /** The most of the heap {@code run} takes above what was in use when it started, in bytes. */
    private static long heapPeak(Runnable run) throws Exception {
        var memory = ManagementFactory.getMemoryMXBean();
        System.gc();
        long base = memory.getHeapMemoryUsage().getUsed();
        var peak = new AtomicLong(base);
        var sampler = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                peak.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(), Math::max);
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        sampler.setDaemon(true);
        sampler.start();
        try {
            run.run();
        } finally {
            sampler.interrupt();
            sampler.join();
        }
        return peak.get() - base;
    }

    @Test
    @DisplayName("each accepted format at its largest size -- 65,535 x 65,535 -- is answered in bounded memory and time, from a file of a few hundred bytes")
    void theLargestDeclarationsAreBounded(@TempDir Path dir) throws Exception {
        for (var crafted : new Object[][]{
                {"baseline.jpg", TestImages.jpegDeclaring(0xC0, 65535, 65535, 3, 1)},
                {"baseline16384.jpg", TestImages.jpegDeclaring(0xC0, 16384, 16384, 3, 1)},
                {"grey.png", TestImages.uniformGreyPng(65535, 300)},
                {"tall.png", TestImages.uniformGreyPng(300, 65535)},
                {"two.gif", TestImages.gifDeclaring(65535, 65535)}}) {
            Path f = file(dir, (String) crafted[0], (byte[]) crafted[1]);
            assertTrue(Files.size(f) < 200_000, crafted[0] + " is small: " + Files.size(f));
            var result = new CloudImageCopy.Made[1];
            long start = System.nanoTime();
            long peak = heapPeak(() -> result[0] = CloudImageCopy.of(f, Duration.ofSeconds(3)));
            long seconds = (System.nanoTime() - start) / 1_000_000_000L;
            assertTrue(seconds < 15, crafted[0] + " took " + seconds + " s");
            assertTrue(peak < 400L << 20, crafted[0] + " took " + (peak >> 20) + " MB of heap");
            assertNotNull(result[0], crafted[0] + ": an answer");
        }
        // Mutation: no time limit -> minutes.
    }

    @Test
    @DisplayName("a reader is given the chunks it draws from and no other: a palette PNG's 60 MB suggested palette is never parsed")
    void theReaderGetsThePictureAlone(@TempDir Path dir) throws Exception {
        // A palette image: the JDK's reader parses every chunk of one whatever it is told -- an sPLT
        // into five arrays of ints, several times its size.
        var palette = new java.io.ByteArrayOutputStream();
        palette.writeBytes(new byte[]{0, 0, 0, (byte) 255, (byte) 255, (byte) 255});
        byte[] row = new byte[1 + 40];
        for (int i = 1; i < row.length; i++) row[i] = (byte) (i % 2);
        byte[] splt = new byte[6 + 6 * 10_000_000];
        System.arraycopy("bomb\0".getBytes(StandardCharsets.ISO_8859_1), 0, splt, 0, 5);
        splt[5] = 8;
        byte[] png = TestImages.pngOf("IHDR", TestImages.ihdr(40, 30, 8, 3), "PLTE", palette.toByteArray(),
                "sPLT", splt, "IDAT", TestImages.idat(row, 30), "IEND", new byte[0]);
        splt = null;
        Path f = file(dir, "palette.png", png);
        var result = new CloudImageCopy.Made[1];
        long peak = heapPeak(() -> result[0] = CloudImageCopy.of(f));
        assertNotNull(result[0].copy(), result[0].privateBecause());
        assertEquals("data of another program", result[0].copy().metadata());
        assertTrue(peak < 150L << 20, (peak >> 20) + " MB of heap: the suggested palette was parsed");
        // Mutation: hand the reader the file whole -> hundreds of megabytes of arrays.
    }

    @Test
    @DisplayName("a picture far larger than its copy is read at a fraction of its pixels: the heap it takes is its copy's order, not its own")
    void aLargePictureIsReadAtAFraction(@TempDir Path dir) throws Exception {
        // Sixteen thousand rows of sixteen thousand grey pixels: 256 MB read whole, a gigabyte as RGB.
        Path f = file(dir, "large.png", TestImages.uniformGreyPng(16000, 16000));
        var result = new CloudImageCopy.Made[1];
        long peak = heapPeak(() -> result[0] = CloudImageCopy.of(f, Duration.ofSeconds(25)));
        var copy = result[0].copy();
        assertNotNull(copy, result[0].privateBecause());
        assertEquals(16000, copy.originalWidth());
        assertEquals(copy.width(), copy.height());
        assertTrue(copy.width() <= CloudImageCopy.MAX_EDGE, "within the tier: " + copy.width());
        assertTrue(peak < 200L << 20, "read at a fraction of its pixels, not whole: " + (peak >> 20) + " MB of heap");
        // Mutation: read every pixel (no subsampling) -> over a gigabyte before the copy is begun.
    }

    @Test
    @DisplayName("past its time the read is aborted from the reading thread and the file stays private, saying so -- a PNG's and a JPEG's; checks run one at a time")
    void pastItsTimeItStaysPrivate(@TempDir Path dir) throws Exception {
        // Sixteen thousand rows of sixteen thousand grey pixels: under a megabyte, two seconds of
        // inflating; and a JPEG of 16,384 x 16,384 of a few hundred bytes, a second and more of
        // decoding -- whose reader throws when another thread aborts it.
        Path big = file(dir, "big.png", TestImages.uniformGreyPng(16000, 16000));
        Path jpeg = file(dir, "big.jpg", TestImages.jpegDeclaring(0xC0, 16384, 16384, 3, 1));
        for (Path f : List.of(big, jpeg)) {
            long start = System.nanoTime();
            var made = CloudImageCopy.of(f, Duration.ofMillis(200));
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertNull(made.copy());
            assertEquals(CloudImageCopy.TOO_LONG, made.privateBecause());
            assertTrue(ms < 800, f.getFileName() + ": the read was aborted at its time, not let run: " + ms + " ms");
        }

        // Two at once queue: one thread, so the second starts when the first has ended.
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            long t0 = System.nanoTime();
            var a = pool.submit(() -> CloudImageCopy.of(big, Duration.ofMillis(700)));
            var b = pool.submit(() -> CloudImageCopy.of(big, Duration.ofMillis(700)));
            assertEquals(CloudImageCopy.TOO_LONG, a.get().privateBecause());
            assertEquals(CloudImageCopy.TOO_LONG, b.get().privateBecause());
            assertTrue((System.nanoTime() - t0) / 1_000_000 >= 1_300, "one after the other, not side by side");
        } finally {
            pool.shutdownNow();
        }
        // Mutation: the Stopper aborts nothing -> each read runs its seconds; a pool of two -> side by side.
    }
}
