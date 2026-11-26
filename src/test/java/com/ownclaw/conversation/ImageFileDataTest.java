package com.ownclaw.conversation;

import com.ownclaw.conversation.TestImages.Entry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What an image's file holds beside its picture, as the cloud model is given it (the owner,
 * 2026-11-26: "it should see everything as it may be important context"): read from files written
 * here by hand, as cameras, phones and editors write them -- and from broken and crafted ones,
 * which it reads as far as it can and never fails on.
 */
class ImageFileDataTest {

    static byte[] jpeg() throws Exception {
        return TestImages.encoded(TestImages.screenshot(64, 48), "jpeg");
    }

    @Test
    @DisplayName("a phone photo: the camera, the time, the place in decimal degrees too, the maker's notes and the thumbnail named and sized")
    void aPhonePhoto() throws Exception {
        byte[] photo = TestImages.withSegment(jpeg(), 0xE1, TestImages.phoneExif());
        String data = ImageFileData.of(photo, "EXIF data");
        assertTrue(data.startsWith("Beside the picture, the file holds: EXIF data.\n"), data);
        for (String line : List.of(
                "EXIF:",
                "Place where it was taken (GPS): 51.477389 N, 0.000389 W, 47.5 m above sea level",
                "ImageDescription: Cabinet door, left side",
                "Make: ExampleCam",
                "Model: X1 Pro",
                "Orientation: 6 (the picture shown to you is already turned upright)",
                "DateTime: 2026:05:01 10:20:30",
                "DateTimeOriginal: 2026:05:01 10:20:30",
                "OffsetTimeOriginal: +01:00",
                "FNumber: 18/10",
                "ExposureTime: 1/120",
                "ISOSpeedRatings: 100",
                "MakerNote: 200 bytes of the camera maker's own data (binary)",
                "UserComment: the rack in the hall",
                "GPSLatitudeRef: N",
                "GPSLatitude: 51, 28, 3860/100",
                "GPSAltitude: 4750/100",
                "GPSVersionID: 2, 2, 0, 0",
                "Of its thumbnail:",
                "  Compression: 6",
                "  Thumbnail: a JPEG picture of 1,234 bytes, not shown to you")) {
            assertTrue(data.contains(line + "\n") || data.endsWith(line), "'" + line + "' in:\n" + data);
        }
        assertFalse(data.contains("ExifIFD") || data.contains("Tag 0x8769") || data.contains("Tag 0x8825"),
                "a directory's pointer is followed, not said: " + data);
        // Mutation: drop the GPS directory's walk -> no place; say the maker's note as hex -> 600 characters of noise.
    }

    @Test
    @DisplayName("XMP, a comment, IPTC's keywords and caption, a colour profile by its name, a program's own text and binary, and what follows the end")
    void theRestOfAJpeg() throws Exception {
        String xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:Description photoshop:City=\"Greenwich\"/></x:xmpmeta>";
        byte[] iptc = TestImages.concat("Photoshop 3.0\0".getBytes(StandardCharsets.ISO_8859_1),
                resource(0x0404, TestImages.concat(dataset(2, 25, "rack"), dataset(2, 25, "cables"),
                        dataset(2, 120, "The hall cabinet, after the move"))),
                resource(0x040C, new byte[]{1, 2, 3, (byte) 0xFF}));
        byte[] photo = jpeg();
        photo = TestImages.withSegment(photo, 0xFE, "Shot for the wiki".getBytes(StandardCharsets.UTF_8));
        photo = TestImages.withSegment(photo, 0xED, iptc);
        photo = TestImages.withSegment(photo, 0xE2, TestImages.concat("ICC_PROFILE\0".getBytes(StandardCharsets.ISO_8859_1),
                new byte[]{1, 1}, icc("Example Display P3")));
        photo = TestImages.withSegment(photo, 0xE1, TestImages.concat(
                "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.ISO_8859_1), xmp.getBytes(StandardCharsets.UTF_8)));
        photo = TestImages.withSegment(photo, 0xE5, "ExampleEditor history: cropped".getBytes(StandardCharsets.UTF_8));
        photo = TestImages.withSegment(photo, 0xE6, new byte[]{0, 1, 2, 3, (byte) 0x80, (byte) 0xFE});
        photo = TestImages.concat(photo, "PK\u0003\u0004 an archive".getBytes(StandardCharsets.ISO_8859_1));
        String data = ImageFileData.of(photo, "XMP data, data of another program");
        for (String part : List.of(
                "XMP:\n" + xmp,
                "Comment:\nShot for the wiki",
                "IPTC:\nKeywords: rack\nKeywords: cables\nCaption/Abstract: The hall cabinet, after the move",
                "Photoshop data 0x040C, 4 bytes of binary data.",
                "A colour profile described as \"Example Display P3\", ",
                "Data of another program (APP5), as text:\nExampleEditor history: cropped",
                "Data of another program (APP6), 6 bytes of binary data.",
                "After the picture's end, 15 bytes of binary data.")) {
            assertTrue(data.contains(part), "'" + part + "' in:\n" + data);
        }
    }

    @Test
    @DisplayName("a PNG: its texts -- plain, compressed, international, XMP --, its time stamp, EXIF, a colour profile, an animation, a program's chunk")
    void aPng() throws Exception {
        byte[] png = TestImages.png(32, 24);
        png = TestImages.withChunk(png, "tEXt", latin1("Software\0ExampleShot 1.0"));
        png = TestImages.withChunk(png, "zTXt", TestImages.concat(latin1("Comment\0\0"), deflate("Before the cabinet's move".getBytes(StandardCharsets.UTF_8))));
        png = TestImages.withChunk(png, "iTXt", TestImages.concat(latin1("Title\0\1\0cs\0"), "Název\0".getBytes(StandardCharsets.UTF_8), deflate("Skříň v hale".getBytes(StandardCharsets.UTF_8))));
        png = TestImages.withChunk(png, "iTXt", TestImages.concat(latin1("XML:com.adobe.xmp\0\0\0\0\0"), "<x:xmpmeta/>".getBytes(StandardCharsets.UTF_8)));
        png = TestImages.withChunk(png, "tIME", new byte[]{0x07, (byte) 0xEA, 5, 1, 10, 20, 30});
        png = TestImages.withChunk(png, "eXIf", java.util.Arrays.copyOfRange(TestImages.phoneExif(), 6, TestImages.phoneExif().length));
        png = TestImages.withChunk(png, "iCCP", TestImages.concat(latin1("Example RGB\0\0"), deflate(icc("Example RGB profile"))));
        png = TestImages.withChunk(png, "acTL", new byte[]{0, 0, 0, 3, 0, 0, 0, 0});
        png = TestImages.withChunk(png, "prVt", new byte[]{9, 8, 7, 0, 1});
        String data = ImageFileData.of(png, "text");
        for (String part : List.of(
                "Text \"Software\":\nExampleShot 1.0",
                "Text \"Comment\":\nBefore the cabinet's move",
                "Text \"Title\" (\"Název\"), in cs:\nSkříň v hale",
                "XMP:\n<x:xmpmeta/>",
                "Last changed (its time stamp): 2026-05-01 10:20:30 UTC",
                "Place where it was taken (GPS): 51.477389 N, 0.000389 W",
                "A colour profile named \"Example RGB\", described as \"Example RGB profile\", ",
                "It is an animation of 3 frames: the first is the picture.",
                "A part named \"prVt\", 5 bytes of binary data.")) {
            assertTrue(data.contains(part), "'" + part + "' in:\n" + data);
        }
    }

    @Test
    @DisplayName("a GIF: its comment, text drawn on it, XMP as GIF writes it, its frames")
    void aGif() throws Exception {
        byte[] gif = TestImages.encoded(TestImages.screenshot(16, 16), "gif");
        // Before the trailer: a comment, an XMP extension written as XMP writes it, a second frame.
        var more = new ByteArrayOutputStream();
        more.writeBytes(new byte[]{0x21, (byte) 0xFE, 5});
        more.writeBytes(latin1("hello"));
        more.write(0);
        more.writeBytes(new byte[]{0x21, (byte) 0xFF, 11});
        more.writeBytes(latin1("XMP DataXMP"));
        more.writeBytes("<?xpacket begin=\"\"?><x:xmpmeta/><?xpacket end=\"w\"?>".getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 256; i++) more.write(i == 0 ? 1 : 256 - i);
        more.write(0);
        more.write(0);
        int trailer = gif.length - 1;
        byte[] frame = java.util.Arrays.copyOfRange(gif, firstFrame(gif), trailer);
        byte[] made = TestImages.concat(java.util.Arrays.copyOf(gif, trailer), more.toByteArray(), frame, new byte[]{0x3B});
        String data = ImageFileData.of(made, "a comment, more frames");
        for (String part : List.of("Comment:\nhello", "XMP:\n<?xpacket begin=\"\"?><x:xmpmeta/><?xpacket end=\"w\"?>",
                "It is an animation of 2 frames: the first is the picture.")) {
            assertTrue(data.contains(part), "'" + part + "' in:\n" + data);
        }
    }

    @Test
    @DisplayName("a file that holds the picture alone has no file data; one whose parts this cannot read still says what was found")
    void nothingOrTheWords() throws Exception {
        assertNull(ImageFileData.of(TestImages.png(16, 16), null));
        assertNull(ImageFileData.of(jpeg(), null));
        assertEquals("Beside the picture, the file holds: data hidden where the picture's own belongs.",
                ImageFileData.of(TestImages.png(16, 16), "data hidden where the picture's own belongs"));
    }

    @Test
    @DisplayName("crafted EXIF: a directory naming itself, a chain of Exif directories each naming the next, values outside it -- read as far as it goes, never failing")
    void craftedExif() throws Exception {
        // IFD0 at 8 whose next directory is itself, and whose Exif pointer names IFD0 too.
        byte[] loop = {'I', 'I', 42, 0, 8, 0, 0, 0, 2, 0,
                0x0F, 0x01, 2, 0, 3, 0, 0, 0, 'A', 'B', 0, 0,
                0x69, (byte) 0x87, 4, 0, 1, 0, 0, 0, 8, 0, 0, 0,
                8, 0, 0, 0};
        String data = ImageFileData.of(TestImages.withSegment(jpeg(), 0xE1, exifOf(loop)), "EXIF data");
        assertEquals(1, data.split("Make: AB", -1).length - 1, data);

        // Half a million Exif directories, each naming the next: walked one beneath the first only, and the
        // second's pointer said -- followed, they would recurse half a million deep.
        int many = 500_000;
        var chain = java.nio.ByteBuffer.allocate(8 + 18 * many).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        chain.put(new byte[]{'I', 'I', 42, 0}).putInt(8);
        for (int i = 0; i < many; i++) {
            int at = 8 + 18 * i;
            chain.putShort((short) 1).putShort((short) 0x8769).putShort((short) 4).putInt(1).putInt(at + 18).putInt(0);
        }
        byte[] png = TestImages.withChunk(TestImages.png(8, 8), "eXIf", chain.array());
        String walked = assertDoesNotThrow(() -> ImageFileData.of(png, "EXIF data"));
        assertEquals(1, walked.split("Tag 0x8769: ", -1).length - 1, walked);

        // Directories that all lie over one, and entries that all name one long value: each read once.
        var over = java.nio.ByteBuffer.allocate(8 + 2 + 12 * 2000 + 4 + 4000).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        over.put(new byte[]{'I', 'I', 42, 0}).putInt(8).putShort((short) 2000);
        int text = 8 + 2 + 12 * 2000 + 4;
        for (int i = 0; i < 2000; i++) {
            over.putShort((short) 0x010E).putShort((short) 2).putInt(4000).putInt(text);
        }
        over.putInt(0);
        for (int i = 0; i < 4000; i++) over.put((byte) 'x');
        data = ImageFileData.of(TestImages.withSegment(jpeg(), 0xE1, exifOf(over.array())), "EXIF data");
        assertEquals(1, data.split("x{4000}", -1).length - 1, "the value said once");
        assertTrue(data.length() < 4000 + 2000 * 120, "and the rest named only: " + data.length());
        var nested = java.nio.ByteBuffer.allocate(8 + 2 + 12 * 3000 + 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        nested.put(new byte[]{'I', 'I', 42, 0}).putInt(8).putShort((short) 3000);
        for (int i = 0; i < 3000; i++) nested.putShort((short) 0x8769).putShort((short) 4).putInt(1).putInt(9 + i);
        nested.putInt(0);
        data = ImageFileData.of(TestImages.withSegment(jpeg(), 0xE1, exifOf(nested.array())), "EXIF data");
        assertTrue(data.length() < 3000 * 120, "three thousand directories over the first, none read: " + data.length());

        // An entry whose value lies past the data's end; one of a thousand numbers.
        byte[] outside = TestImages.tiff(List.of(
                new Entry(0x010E, 2, 4000, new byte[8], null),
                Entry.shorts(0x012D, new int[1000])), null);
        // The first entry's value is said to be 4000 bytes, of which there are 8: its offset is fine, its end not.
        data = ImageFileData.of(TestImages.withSegment(jpeg(), 0xE1, exifOf(outside)), "EXIF data");
        assertTrue(data.contains("ImageDescription: its value lies outside the EXIF data, not read"), data);
        assertTrue(data.contains("TransferFunction: 1000 numbers, 2,000 bytes of binary data"), data);
    }

    @Test
    @DisplayName("text compressed to unpack past what an upload may be is not unpacked, and said so; the rest is still read")
    void anUnpackingBomb() throws Exception {
        byte[] zeros = new byte[3 << 20];
        byte[] png = TestImages.withChunk(TestImages.png(8, 8), "zTXt", TestImages.concat(latin1("Bomb\0\0"), deflate(zeros)));
        png = TestImages.withChunk(png, "tEXt", latin1("Software\0ExampleShot"));
        String data = ImageFileData.of(png, "text", 1 << 20);
        assertTrue(data.contains("Unpacked, the compressed text \"Bomb\" takes the file's text past 1,048,576 bytes"), data);
        assertTrue(data.contains("Text \"Software\":\nExampleShot"), data);
        assertTrue(data.length() < 2000, "nothing of it is said: " + data.length());
    }

    @Test
    @DisplayName("ten thousand files broken at random from real ones: each read as far as it goes, none failing, none taking long")
    void brokenAtRandom() throws Exception {
        byte[] photo = TestImages.withSegment(jpeg(), 0xE1, TestImages.phoneExif());
        byte[] png = TestImages.withChunk(TestImages.png(16, 16), "eXIf",
                java.util.Arrays.copyOfRange(TestImages.phoneExif(), 6, TestImages.phoneExif().length));
        png = TestImages.withChunk(png, "iTXt", TestImages.concat(latin1("Title\0\1\0cs\0N\0"), deflate(latin1("text"))));
        byte[] gif = TestImages.encoded(TestImages.screenshot(16, 16), "gif");
        var random = new Random(26_11_2026L);
        long started = System.nanoTime();
        for (byte[] original : List.of(photo, png, gif)) {
            for (int i = 0; i < 3_400; i++) {
                byte[] broken = original.clone();
                int changes = 1 + random.nextInt(8);
                for (int c = 0; c < changes; c++) broken[random.nextInt(broken.length)] = (byte) random.nextInt(256);
                if (random.nextInt(4) == 0) broken = java.util.Arrays.copyOf(broken, random.nextInt(broken.length));
                byte[] file = broken;
                int step = i;
                assertDoesNotThrow(() -> ImageFileData.of(file, "EXIF data"), () -> "broken at step " + step);
            }
        }
        assertTrue(System.nanoTime() - started < 60_000_000_000L, "ten thousand small files in a minute");
    }

    // ── Files written here ──

    static byte[] latin1(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    static byte[] deflate(byte[] data) {
        var deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        var out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1 << 16];
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
        deflater.end();
        return out.toByteArray();
    }

    static byte[] exifOf(byte[] tiff) {
        return TestImages.concat(latin1("Exif\0\0"), tiff);
    }

    /** A Photoshop image resource: 8BIM, its id, an empty name, its size, its data padded to even. */
    static byte[] resource(int id, byte[] data) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(latin1("8BIM"));
        out.write(id >>> 8);
        out.write(id);
        out.write(0);
        out.write(0);
        TestImages.int32(out, data.length);
        out.writeBytes(data);
        if (data.length % 2 == 1) out.write(0);
        return out.toByteArray();
    }

    static byte[] dataset(int record, int dataset, String text) {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        return TestImages.concat(new byte[]{0x1C, (byte) record, (byte) dataset, (byte) (b.length >>> 8), (byte) b.length}, b);
    }

    /** An ICC profile of a v2 'desc' tag alone. */
    static byte[] icc(String description) {
        byte[] text = latin1(description + "\0");
        var tag = java.nio.ByteBuffer.allocate(12 + text.length);
        tag.put(latin1("desc")).putInt(0).putInt(text.length).put(text);
        int at = 128 + 4 + 12;
        var p = java.nio.ByteBuffer.allocate(at + tag.capacity());
        p.position(128);
        p.putInt(1).put(latin1("desc")).putInt(at).putInt(tag.capacity());
        p.put(tag.array());
        return p.array();
    }

    /** Where a GIF's first image descriptor begins: after its header, screen and colour table, and its extensions. */
    static int firstFrame(byte[] gif) {
        int packed = gif[10] & 0xFF;
        int p = 13 + ((packed & 0x80) != 0 ? 3 << ((packed & 7) + 1) : 0);
        while ((gif[p] & 0xFF) == 0x21) {
            p += 2;
            while ((gif[p] & 0xFF) != 0) p += 1 + (gif[p] & 0xFF);
            p++;
        }
        return p;
    }
}
