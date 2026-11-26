package com.ownclaw.conversation;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What an image's file holds beside its picture, as text a model can read: its EXIF -- the camera,
 * the time, the place, as decimal degrees too --, its XMP and IPTC, its text and comments, its time
 * stamp, the colour profile's name, and whatever else it holds, named and sized. The owner's
 * decision of 2026-11-26: an image he lets the cloud model see, it sees whole -- "it should see
 * everything as it may be important context".
 * <p>
 * Read from the file's own structure, as {@link CloudImageCopy} walks it, but forgiving: a part
 * that cannot be read is said to be there, and the walk goes on where it can. Binary data -- a
 * maker's own notes, a thumbnail, a colour profile, a run of more than {@link #MANY_NUMBERS}
 * numbers -- is named and sized, not spelled out byte by
 * byte: no model reads a picture or a camera's notes from their bytes, and a skill can read the
 * file. Compressed text is unpacked up to what an upload may be in all ({@link #UNPACK_LIMIT}); a
 * file whose text unpacks to more was made to, and is said to.
 */
public final class ImageFileData {

    private ImageFileData() {}

    /**
     * The most a file's compressed parts are unpacked to, in all: 100 MB, what an upload may be
     * (FileUploadController). Text that large could have been sent as it is; a few kilobytes that
     * unpack past it were made to.
     */
    static final long UNPACK_LIMIT = 100L << 20;

    /**
     * The file data of the image in {@code file}, or null when it holds nothing beside the picture.
     *
     * @param beside what {@link CloudImageCopy} found beside the picture, in words -- "EXIF data, a
     *               colour profile" -- or null; said first, as it found more than this reads: data
     *               hidden in a table, a setting of the wrong length
     */
    public static String of(byte[] file, String beside) {
        return of(file, beside, UNPACK_LIMIT);
    }

    static String of(byte[] file, String beside, long unpackLimit) {
        var out = new Out(unpackLimit);
        if (file != null) {
            try {
                if (starts(file, 0, new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'})) png(file, out);
                else if (file.length > 2 && (file[0] & 0xFF) == 0xFF && (file[1] & 0xFF) == 0xD8) jpeg(file, out);
                else if (starts(file, 0, "GIF8".getBytes(StandardCharsets.ISO_8859_1))) gif(file, out);
            } catch (RuntimeException broken) {
                out.section("The rest of the file's structure could not be read here; a skill can read the file.");
            }
        }
        if (beside == null && out.isEmpty()) return null;
        var sb = new StringBuilder();
        if (beside != null) sb.append("Beside the picture, the file holds: ").append(beside).append(".\n");
        sb.append(out.text());
        return sb.toString().strip();
    }

    // ── PNG ──

    /** The chunks that are the picture and how to draw it, as CloudImageCopy counts them: nothing to say. */
    private static final Set<String> PICTURE = Set.of("IHDR", "PLTE", "IDAT", "IEND", "tRNS", "cHRM", "gAMA", "sRGB",
            "sBIT", "bKGD", "pHYs", "hIST", "cICP");

    private static void png(byte[] d, Out out) {
        int p = 8;
        int frames = 0;
        while (p + 8 <= d.length) {
            long length = u32(d, p, false);
            String type = latin1(d, p + 4, p + 8);
            int start = p + 8;
            if (length > d.length - start) {
                out.section("A part named \"" + printable(type) + "\" runs past the file's end; it was not read.");
                return;
            }
            int end = start + (int) length;
            switch (type) {
                case "IEND" -> {
                    trailing(d, Math.min(d.length, end + 4), out);
                    if (frames > 0) out.section("It is an animation of " + frames + " frames: the first is the picture.");
                    return;
                }
                case "tEXt" -> {
                    int nul = indexOf(d, start, end, (byte) 0);
                    if (nul < 0) out.section("A text with no keyword, " + count(length) + ": " + text(d, start, end));
                    else out.block("Text \"" + latin1(d, start, nul) + "\":", latin1(d, nul + 1, end));
                }
                case "zTXt" -> {
                    int nul = indexOf(d, start, end, (byte) 0);
                    if (nul < 0 || nul + 2 > end) {
                        out.section("A compressed text with no keyword, " + count(length) + ", not read.");
                    } else {
                        String key = latin1(d, start, nul);
                        byte[] unpacked = out.unpack(d, nul + 2, end, "the compressed text \"" + key + "\"");
                        if (unpacked != null) out.block("Text \"" + key + "\":", latin1(unpacked, 0, unpacked.length));
                    }
                }
                case "iTXt" -> itxt(d, start, end, out);
                case "eXIf" -> exif(d, start, end, "EXIF", out);
                case "tIME" -> {
                    if (length == 7) {
                        out.section(String.format(Locale.ROOT, "Last changed (its time stamp): %04d-%02d-%02d %02d:%02d:%02d UTC",
                                u16(d, start, false), d[start + 2] & 0xFF, d[start + 3] & 0xFF, d[start + 4] & 0xFF,
                                d[start + 5] & 0xFF, d[start + 6] & 0xFF));
                    } else {
                        out.section("A time stamp of the wrong length, " + count(length) + ".");
                    }
                }
                case "iCCP" -> {
                    int nul = indexOf(d, start, end, (byte) 0);
                    String name = nul < 0 ? "" : latin1(d, start, nul);
                    byte[] profile = nul < 0 || nul + 2 > end ? null : out.unpack(d, nul + 2, end, "its colour profile");
                    out.section(colourProfile(name, profile, length));
                }
                case "acTL" -> {
                    if (length >= 4) frames = (int) Math.min(Integer.MAX_VALUE, u32(d, start, false));
                }
                case "fcTL", "fdAT" -> { }
                default -> {
                    if (!PICTURE.contains(type)) out.part("A part named \"" + printable(type) + "\"", d, start, end);
                }
            }
            p = end + 4;
        }
        out.section("The file ends before the picture's end.");
    }

    /** An iTXt chunk: a keyword, whether it is compressed, a language, a translated keyword, UTF-8 text. */
    private static void itxt(byte[] d, int start, int end, Out out) {
        int nul = indexOf(d, start, end, (byte) 0);
        if (nul < 0 || nul + 3 > end) {
            out.section("A text with no keyword, " + count(end - start) + ", not read.");
            return;
        }
        String key = latin1(d, start, nul);
        boolean compressed = d[nul + 1] != 0;
        int lang = indexOf(d, nul + 3, end, (byte) 0);
        int translated = lang < 0 ? -1 : indexOf(d, lang + 1, end, (byte) 0);
        if (translated < 0) {
            out.section("A text \"" + key + "\" whose structure is broken, " + count(end - start) + ", not read.");
            return;
        }
        String language = latin1(d, nul + 3, lang), translation = utf8(d, lang + 1, translated);
        String label = "Text \"" + key + "\"" + (translation.isBlank() ? "" : " (\"" + translation + "\")")
                + (language.isBlank() ? "" : ", in " + language) + ":";
        if (key.equals("XML:com.adobe.xmp")) label = "XMP:";
        if (compressed) {
            byte[] unpacked = out.unpack(d, translated + 1, end, "the compressed text \"" + key + "\"");
            if (unpacked != null) out.block(label, utf8(unpacked, 0, unpacked.length));
        } else {
            out.block(label, utf8(d, translated + 1, end));
        }
    }

    // ── JPEG ──

    private static void jpeg(byte[] d, Out out) {
        var icc = new TreeMap<Integer, byte[]>();
        var extended = new HashMap<String, TreeMap<Long, byte[]>>();
        int p = 2;
        try {
            while (p < d.length) {
                if ((d[p] & 0xFF) != 0xFF) {
                    out.section("The file's structure breaks off; the rest, " + count(d.length - p) + ", was not read.");
                    return;
                }
                while (p < d.length && (d[p] & 0xFF) == 0xFF) p++;
                if (p >= d.length) break;
                int marker = d[p++] & 0xFF;
                if (marker == 0xD9) {
                    trailing(d, p, out);
                    return;
                }
                if (marker == 0x01 || marker >= 0xD0 && marker <= 0xD7) continue;
                if (p + 2 > d.length) break;
                int length = u16(d, p, false);
                int start = p + 2, end = p + length;
                if (length < 2 || end > d.length) {
                    out.section("A part runs past the file's end; it was not read.");
                    return;
                }
                if (marker == 0xDA) {
                    p = afterScan(d, end);
                    continue;
                }
                if (marker >= 0xE0 && marker <= 0xEF || marker == 0xFE) segment(marker, d, start, end, out, icc, extended);
                p = end;
            }
            out.section("The file ends before the picture's end.");
        } finally {
            if (!icc.isEmpty()) {
                var all = new ByteArrayOutputStream();
                icc.values().forEach(all::writeBytes);
                out.section(colourProfile("", all.toByteArray(), all.size()));
            }
            for (var parts : extended.values()) {
                var all = new ByteArrayOutputStream();
                parts.values().forEach(all::writeBytes);
                out.block("XMP, continued (extended XMP):", utf8(all.toByteArray(), 0, all.size()));
            }
        }
    }

    /** Where the entropy-coded data after a scan's header ends: the next marker that is not a restart. */
    private static int afterScan(byte[] d, int from) {
        for (int i = from; i + 1 < d.length; i++) {
            if ((d[i] & 0xFF) != 0xFF) continue;
            int next = d[i + 1] & 0xFF;
            if (next != 0x00 && next != 0xFF && (next < 0xD0 || next > 0xD7)) return i;
        }
        return d.length;
    }

    private static void segment(int marker, byte[] d, int start, int end, Out out, TreeMap<Integer, byte[]> icc,
                                Map<String, TreeMap<Long, byte[]>> extended) {
        int length = end - start;
        switch (marker) {
            case 0xE0 -> {
                boolean jfif = starts(d, start, "JFIF\0"), jfxx = starts(d, start, "JFXX\0");
                if (jfif && length == 14 && d[start + 12] == 0 && d[start + 13] == 0) return;
                if (jfif || jfxx) out.section("A thumbnail picture (JFIF), " + count(length) + ", not shown to you.");
                else out.part("Data of another program (APP0)", d, start, end);
            }
            case 0xE1 -> {
                if (starts(d, start, "Exif\0\0")) {
                    exif(d, start + 6, end, "EXIF", out);
                } else if (starts(d, start, "http://ns.adobe.com/xap/1.0/\0")) {
                    out.block("XMP:", utf8(d, start + 29, end));
                } else if (starts(d, start, "http://ns.adobe.com/xmp/extension/\0") && length >= 35 + 40) {
                    String guid = latin1(d, start + 35, start + 67);
                    long offset = u32(d, start + 71, false);
                    extended.computeIfAbsent(guid, k -> new TreeMap<>()).put(offset,
                            java.util.Arrays.copyOfRange(d, start + 75, end));
                } else {
                    out.part("Data of another program (APP1)", d, start, end);
                }
            }
            case 0xE2 -> {
                if (starts(d, start, "ICC_PROFILE\0") && length >= 14) {
                    icc.put(d[start + 12] & 0xFF, java.util.Arrays.copyOfRange(d, start + 14, end));
                } else if (starts(d, start, "MPF\0")) {
                    out.section("Multi-picture data (MPF), " + count(length) + ": the file holds more pictures than the "
                            + "one shown to you -- a second exposure, a depth or gain map -- after its end.");
                } else {
                    out.part("Data of another program (APP2)", d, start, end);
                }
            }
            case 0xED -> {
                if (starts(d, start, "Photoshop 3.0\0")) photoshop(d, start + 14, end, out);
                else out.part("Data of another program (APP13)", d, start, end);
            }
            case 0xEE -> {
                if (!(starts(d, start, "Adobe") && length == 12)) out.part("Data of another program (APP14)", d, start, end);
            }
            case 0xFE -> out.block("Comment:", text(d, start, end));
            default -> out.part("Data of another program (APP" + (marker - 0xE0) + ")", d, start, end);
        }
    }

    /** Photoshop's image resources: IPTC among them. */
    private static void photoshop(byte[] d, int p, int end, Out out) {
        while (p + 12 <= end && starts(d, p, "8BIM")) {
            int id = u16(d, p + 4, false);
            int nameLength = d[p + 6] & 0xFF;
            int q = p + 6 + 1 + nameLength;
            if (((1 + nameLength) & 1) == 1) q++;
            if (q + 4 > end) break;
            long size = u32(d, q, false);
            int start = q + 4;
            if (size > end - start) {
                out.section("A Photoshop part runs past its segment's end; it was not read.");
                return;
            }
            int stop = start + (int) size;
            if (id == 0x0404) iptc(d, start, stop, out);
            else out.part(String.format(Locale.ROOT, "Photoshop data 0x%04X", id), d, start, stop);
            p = stop + (int) (size & 1);
        }
    }

    // ── GIF ──

    private static void gif(byte[] d, Out out) {
        int packed = d[10] & 0xFF;
        int p = 13 + ((packed & 0x80) != 0 ? 3 << ((packed & 7) + 1) : 0);
        int frames = 0;
        while (p < d.length) {
            int b = d[p++] & 0xFF;
            if (b == 0x3B) {
                trailing(d, p, out);
                break;
            }
            if (b == 0x2C) {
                frames++;
                p += 8;
                int local = d[p++] & 0xFF;
                if ((local & 0x80) != 0) p += 3 << ((local & 7) + 1);
                p = subBlocks(d, p + 1, null);
            } else if (b == 0x21) {
                int label = d[p++] & 0xFF;
                if (label == 0xFF) {
                    int idLength = d[p++] & 0xFF;
                    String id = latin1(d, p, p + idLength);
                    p += idLength;
                    int raw = p;
                    var data = new ByteArrayOutputStream();
                    p = subBlocks(d, p, data);
                    if (id.equals("NETSCAPE2.0") || id.equals("ANIMEXTS1.0")) continue;
                    if (id.startsWith("XMP Data")) {
                        out.block("XMP:", gifXmp(d, raw, p));
                    } else if (id.startsWith("ICCRGBG1")) {
                        out.section(colourProfile("", data.toByteArray(), data.size()));
                    } else {
                        byte[] bytes = data.toByteArray();
                        out.part("Data of another program (\"" + printable(id) + "\")", bytes, 0, bytes.length);
                    }
                } else {
                    var data = new ByteArrayOutputStream();
                    p = subBlocks(d, p, data);
                    byte[] bytes = data.toByteArray();
                    if (label == 0xFE) out.block("Comment:", text(bytes, 0, bytes.length));
                    else if (label == 0x01) out.block("Text drawn on the picture:", text(bytes, Math.min(12, bytes.length), bytes.length));
                    else if (label != 0xF9) out.part(String.format(Locale.ROOT, "An extension 0x%02X", label), bytes, 0, bytes.length);
                }
            } else {
                out.section("The file's structure breaks off; the rest, " + count(d.length - p + 1) + ", was not read.");
                break;
            }
        }
        if (frames > 1) out.section("It is an animation of " + frames + " frames: the first is the picture.");
    }

    /** The sub-blocks from {@code p}, their data written to {@code data} when it is given: where they end. */
    private static int subBlocks(byte[] d, int p, ByteArrayOutputStream data) {
        while (p < d.length) {
            int size = d[p++] & 0xFF;
            if (size == 0) return p;
            int stop = Math.min(d.length, p + size);
            if (data != null) data.write(d, p, stop - p);
            p = stop;
        }
        return p;
    }

    /**
     * GIF's XMP: the packet written raw, its bytes read as sub-block sizes, and a "magic trailer"
     * after it -- so the text is the raw bytes to the packet's end.
     */
    private static String gifXmp(byte[] d, int from, int to) {
        String raw = utf8(d, from, Math.max(from, to - 1));
        int end = raw.indexOf("<?xpacket end=");
        if (end >= 0) {
            int close = raw.indexOf("?>", end);
            return raw.substring(0, close < 0 ? raw.length() : close + 2);
        }
        int meta = raw.lastIndexOf("</x:xmpmeta>");
        return meta >= 0 ? raw.substring(0, meta + "</x:xmpmeta>".length()) : raw;
    }

    // ── What follows the picture's end ──

    private static void trailing(byte[] d, int from, Out out) {
        if (from >= d.length) return;
        int length = d.length - from;
        if (length >= 3 && (d[from] & 0xFF) == 0xFF && (d[from + 1] & 0xFF) == 0xD8 && (d[from + 2] & 0xFF) == 0xFF) {
            out.section(count(length) + " after the picture's end, starting with another JPEG picture, not shown to you.");
        } else {
            out.part("After the picture's end", d, from, d.length);
        }
    }

    // ── EXIF ──

    /** The directories EXIF holds: the picture's, its thumbnail's, and the Exif, GPS and interoperability ones beneath. */
    private enum Directory { MAIN, THUMBNAIL, EXIF, GPS, INTEROP }

    private static final int EXIF_IFD = 0x8769, GPS_IFD = 0x8825, INTEROP_IFD = 0xA005;

    /** The most numbers of one entry said one by one; more are named and sized ({@link #value}). */
    static final int MANY_NUMBERS = 64;

    /** The TIFF structure EXIF is, from {@code start} to {@code end} of {@code d}. */
    private static void exif(byte[] d, int start, int end, String title, Out out) {
        var lines = new ArrayList<String>();
        try {
            boolean little;
            if (starts(d, start, "II")) little = true;
            else if (starts(d, start, "MM")) little = false;
            else {
                out.section(title + ": " + count(end - start) + " in no form this reads (not TIFF).");
                return;
            }
            var tiff = new Tiff(d, start, end, little);
            if (tiff.u16(2) != 42) {
                out.section(title + ": " + count(end - start) + " in no form this reads (not TIFF).");
                return;
            }
            var gps = new Gps();
            long ifd0 = tiff.u32(4);
            long ifd1 = directory(tiff, ifd0, Directory.MAIN, lines, gps);
            if (ifd1 > 0) {
                var thumb = new ArrayList<String>();
                directory(tiff, ifd1, Directory.THUMBNAIL, thumb, gps);
                if (!thumb.isEmpty()) {
                    lines.add("Of its thumbnail:");
                    for (String t : thumb) lines.add("  " + t);
                }
            }
            String position = gps.position();
            if (position != null) lines.add(0, position);
        } catch (RuntimeException broken) {
            lines.add("(The rest of its EXIF data could not be read.)");
        }
        if (!lines.isEmpty()) out.block(title + ":", String.join("\n", lines));
    }

    /**
     * Where a TIFF structure lies in a file, and its byte order -- offsets are from its start -- and
     * which of its bytes a directory or a value has been read from: each is read once. A crafted
     * file whose directories all name the same bytes, or whose entries all name one long value,
     * would otherwise be said thousands of times over from a few kilobytes.
     */
    private static final class Tiff {
        private final byte[] d;
        private final int start, end;
        private final boolean little;
        private final java.util.BitSet read = new java.util.BitSet();

        Tiff(byte[] d, int start, int end, boolean little) {
            this.d = d;
            this.start = start;
            this.end = end;
            this.little = little;
        }

        byte[] d() {
            return d;
        }

        int start() {
            return start;
        }

        boolean little() {
            return little;
        }

        int length() {
            return end - start;
        }

        /** Whether {@code size} bytes at {@code at} were read from by no other part; they are now. */
        boolean claim(long at, long size) {
            check(at, size);
            int from = (int) at, to = (int) (at + size);
            int next = read.nextSetBit(from);
            if (next >= 0 && next < to) return false;
            read.set(from, to);
            return true;
        }

        int u16(long at) {
            check(at, 2);
            return ImageFileData.u16(d, (int) (start + at), little);
        }

        long u32(long at) {
            check(at, 4);
            return ImageFileData.u32(d, (int) (start + at), little);
        }

        void check(long at, long size) {
            if (at < 0 || size < 0 || at + size > length()) throw new IndexOutOfBoundsException("outside the EXIF data");
        }
    }

    /** What the GPS directory says of the place, gathered to be said in decimal degrees too. */
    private static final class Gps {
        String latitudeRef, longitudeRef;
        double[] latitude, longitude;
        Double altitude;
        boolean below;

        String position() {
            if (latitude == null || longitude == null || latitude.length < 3 || longitude.length < 3) return null;
            double lat = latitude[0] + latitude[1] / 60 + latitude[2] / 3600;
            double lon = longitude[0] + longitude[1] / 60 + longitude[2] / 3600;
            if (!Double.isFinite(lat) || !Double.isFinite(lon)) return null;
            String s = String.format(Locale.ROOT, "Place where it was taken (GPS): %.6f %s, %.6f %s", lat,
                    latitudeRef == null ? "N" : latitudeRef, lon, longitudeRef == null ? "E" : longitudeRef);
            if (altitude != null && Double.isFinite(altitude)) {
                s += String.format(Locale.ROOT, ", %.1f m %s sea level", altitude, below ? "below" : "above");
            }
            return s;
        }
    }

    /** The entries of the directory at {@code at}, said as lines; the offset of the next directory, or 0. */
    private static long directory(Tiff t, long at, Directory kind, List<String> lines, Gps gps) {
        if (at == 0) return 0;
        int entries = t.u16(at);
        long next = at + 2 + 12L * entries;
        boolean followed = next + 4 <= t.length();
        if (!t.claim(at, 2 + 12L * entries + (followed ? 4 : 0))) {
            lines.add("(A directory that lies over another part of the EXIF data was not read.)");
            return 0;
        }
        Long thumbOffset = null, thumbLength = null;
        for (int i = 0; i < entries; i++) {
            long e = at + 2 + 12L * i;
            int tag = t.u16(e), type = t.u16(e + 2);
            long count = t.u32(e + 4);
            int size = switch (type) {
                case 1, 2, 6, 7 -> 1;
                case 3, 8 -> 2;
                case 4, 9, 11, 13 -> 4;
                case 5, 10, 12 -> 8;
                default -> 0;
            };
            String name = name(kind, tag);
            if (size == 0) {
                lines.add(name + ": a value of a type this does not read (" + type + ")");
                continue;
            }
            long bytes = count * size;
            long value = bytes <= 4 ? e + 8 : t.u32(e + 8);
            if (bytes > t.length() || value < 0 || value + bytes > t.length()) {
                lines.add(name + ": its value lies outside the EXIF data, not read");
                continue;
            }
            if (bytes > 4 && !t.claim(value, bytes)) {
                lines.add(name + ": its value lies over another part of the EXIF data, not read again");
                continue;
            }
            // Each beneath the one it belongs to only, so the walk is three deep at most: a chain of
            // Exif directories each naming another would otherwise recurse as deep as the file is long.
            boolean top = kind == Directory.MAIN || kind == Directory.THUMBNAIL;
            Directory sub = top && tag == EXIF_IFD ? Directory.EXIF : top && tag == GPS_IFD ? Directory.GPS
                    : kind == Directory.EXIF && tag == INTEROP_IFD ? Directory.INTEROP : null;
            if (sub != null && (type == 4 || type == 13) && count == 1) {
                try {
                    directory(t, t.u32(value), sub, lines, gps);
                } catch (RuntimeException broken) {
                    lines.add("(A part of its EXIF data could not be read.)");
                }
                continue;
            }
            if (kind == Directory.THUMBNAIL && (tag == 0x0201 || tag == 0x0202) && (type == 3 || type == 4) && count == 1) {
                long v = type == 3 ? t.u16(value) : t.u32(value);
                if (tag == 0x0201) thumbOffset = v;
                else thumbLength = v;
                continue;
            }
            String said = value(t, kind, tag, type, count, value, gps);
            lines.add(name + ": " + said);
        }
        if (thumbOffset != null || thumbLength != null) {
            lines.add("Thumbnail: a JPEG picture" + (thumbLength == null ? "" : " of " + count(thumbLength)) + ", not shown to you");
        }
        return followed ? t.u32(next) : 0;
    }

    /** One entry's value, in words. */
    private static String value(Tiff t, Directory kind, int tag, int type, long count, long at, Gps gps) {
        int from = (int) (t.start() + at);
        int size = switch (type) {
            case 3, 8 -> 2;
            case 4, 9, 11, 13 -> 4;
            case 5, 10, 12 -> 8;
            default -> 1;
        };
        int to = (int) (from + count * size);
        byte[] d = t.d();
        // Text in its own form first: the makers' and Windows' own.
        if (kind == Directory.EXIF && tag == 0x927C) return count(count) + " of the camera maker's own data (binary)";
        if (kind != Directory.GPS && kind != Directory.INTEROP && tag >= 0x9C9B && tag <= 0x9C9F && (type == 1 || type == 7)) {
            return trimmed(new String(d, from, to - from, StandardCharsets.UTF_16LE));
        }
        if (kind != Directory.GPS && kind != Directory.INTEROP && tag == 0x02BC && (type == 1 || type == 7)) return utf8(d, from, to).strip();
        if (kind != Directory.GPS && kind != Directory.INTEROP && tag == 0x83BB) {
            var iptc = new Out(0);
            iptc(d, from, to, iptc);
            return "\n" + iptc.text().strip();
        }
        if (type == 7 && (kind == Directory.EXIF && tag == 0x9286 || kind == Directory.GPS && (tag == 27 || tag == 28))) {
            return commented(d, from, to, t.little());
        }
        if (type == 2) {
            String s = text(d, from, to);
            if (kind == Directory.GPS) {
                if (tag == 1) gps.latitudeRef = s.strip();
                if (tag == 3) gps.longitudeRef = s.strip();
            }
            return s;
        }
        if (type == 7 || type == 1 && count > 8) {
            String s = textOrNull(d, from, to);
            if (s != null && !s.isBlank()) return s;
            if (count <= 16) return hex(d, from, to);
            return count(count) + " of binary data";
        }
        // Numbers are said one by one up to MANY_NUMBERS: a longer run -- a transfer curve, a maker's
        // table -- is binary data in another form, read by no one as a list, and named and sized.
        var values = new StringBuilder();
        double[] numbers = new double[(int) Math.min(count, 3)];
        long said = count > MANY_NUMBERS ? numbers.length : count;
        for (long i = 0; i < said; i++) {
            int p = (int) (from + i * size);
            String v;
            double n;
            switch (type) {
                case 1 -> { n = d[p] & 0xFF; v = Long.toString((long) n); }
                case 6 -> { n = d[p]; v = Long.toString((long) n); }
                case 3 -> { n = u16(d, p, t.little()); v = Long.toString((long) n); }
                case 8 -> { n = (short) u16(d, p, t.little()); v = Long.toString((long) n); }
                case 4, 13 -> { n = u32(d, p, t.little()); v = Long.toString((long) n); }
                case 9 -> { n = (int) u32(d, p, t.little()); v = Long.toString((long) n); }
                case 5, 10 -> {
                    long num = u32(d, p, t.little()), den = u32(d, p + 4, t.little());
                    if (type == 10) {
                        num = (int) num;
                        den = (int) den;
                    }
                    n = den == 0 ? Double.NaN : (double) num / den;
                    v = den == 1 ? Long.toString(num) : num + "/" + den;
                }
                case 11 -> { n = Float.intBitsToFloat((int) u32(d, p, t.little())); v = Double.toString(n); }
                default -> {
                    long hi = u32(d, p, t.little()), lo = u32(d, p + 4, t.little());
                    n = Double.longBitsToDouble(t.little() ? lo << 32 | hi : hi << 32 | lo);
                    v = Double.toString(n);
                }
            }
            if (i < numbers.length) numbers[(int) i] = n;
            if (!values.isEmpty()) values.append(", ");
            values.append(v);
        }
        if (kind == Directory.GPS) {
            if (tag == 2) gps.latitude = numbers;
            if (tag == 4) gps.longitude = numbers;
            if (tag == 5 && numbers.length > 0) gps.below = numbers[0] == 1;
            if (tag == 6 && numbers.length > 0) gps.altitude = numbers[0];
        }
        String words = count > MANY_NUMBERS ? count + " numbers, " + count(count * size) + " of binary data" : values.toString();
        if ((kind == Directory.MAIN || kind == Directory.THUMBNAIL) && tag == 0x0112) words += " (the picture shown to you is already turned upright)";
        return words;
    }

    /** UserComment, GPSProcessingMethod: eight bytes naming the character code, then the text. */
    private static String commented(byte[] d, int from, int to, boolean little) {
        if (to - from < 8) return hex(d, from, to);
        String code = latin1(d, from, from + 8);
        int text = from + 8;
        if (code.startsWith("UNICODE")) {
            return trimmed(new String(d, text, to - text, little ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE));
        }
        if (code.startsWith("JIS")) {
            return trimmed(new String(d, text, to - text, java.nio.charset.Charset.forName("ISO-2022-JP")));
        }
        return text(d, text, to);
    }

    /** The names of the tags EXIF uses, by directory. */
    private static String name(Directory kind, int tag) {
        String n = switch (kind) {
            case MAIN, THUMBNAIL, EXIF -> MAIN_TAGS.get(tag);
            case GPS -> GPS_TAGS.get(tag);
            case INTEROP -> INTEROP_TAGS.get(tag);
        };
        return n != null ? n : String.format(Locale.ROOT, "Tag 0x%04X", tag);
    }

    private static Map<Integer, String> tags(Object... pairs) {
        var m = new HashMap<Integer, String>();
        for (int i = 0; i < pairs.length; i += 2) m.put((Integer) pairs[i], (String) pairs[i + 1]);
        return Map.copyOf(m);
    }

    private static final Map<Integer, String> MAIN_TAGS = tags(
            0x00FE, "NewSubfileType", 0x0100, "ImageWidth", 0x0101, "ImageLength", 0x0102, "BitsPerSample",
            0x0103, "Compression", 0x0106, "PhotometricInterpretation", 0x010E, "ImageDescription", 0x010F, "Make",
            0x0110, "Model", 0x0111, "StripOffsets", 0x0112, "Orientation", 0x0115, "SamplesPerPixel",
            0x0116, "RowsPerStrip", 0x0117, "StripByteCounts", 0x011A, "XResolution", 0x011B, "YResolution",
            0x011C, "PlanarConfiguration", 0x0128, "ResolutionUnit", 0x012D, "TransferFunction", 0x0131, "Software",
            0x0132, "DateTime", 0x013B, "Artist", 0x013C, "HostComputer", 0x013E, "WhitePoint",
            0x013F, "PrimaryChromaticities", 0x0211, "YCbCrCoefficients", 0x0212, "YCbCrSubSampling",
            0x0213, "YCbCrPositioning", 0x0214, "ReferenceBlackWhite", 0x02BC, "XMP", 0x4746, "Rating",
            0x4749, "RatingPercent", 0x8298, "Copyright", 0x83BB, "IPTC", 0x829A, "ExposureTime", 0x829D, "FNumber",
            0x8822, "ExposureProgram", 0x8824, "SpectralSensitivity", 0x8827, "ISOSpeedRatings",
            0x8830, "SensitivityType", 0x8832, "RecommendedExposureIndex", 0x9000, "ExifVersion",
            0x9003, "DateTimeOriginal", 0x9004, "DateTimeDigitized", 0x9010, "OffsetTime",
            0x9011, "OffsetTimeOriginal", 0x9012, "OffsetTimeDigitized", 0x9101, "ComponentsConfiguration",
            0x9102, "CompressedBitsPerPixel", 0x9201, "ShutterSpeedValue", 0x9202, "ApertureValue",
            0x9203, "BrightnessValue", 0x9204, "ExposureBiasValue", 0x9205, "MaxApertureValue",
            0x9206, "SubjectDistance", 0x9207, "MeteringMode", 0x9208, "LightSource", 0x9209, "Flash",
            0x920A, "FocalLength", 0x9214, "SubjectArea", 0x927C, "MakerNote", 0x9286, "UserComment",
            0x9290, "SubSecTime", 0x9291, "SubSecTimeOriginal", 0x9292, "SubSecTimeDigitized", 0x9400, "Temperature",
            0x9401, "Humidity", 0x9402, "Pressure", 0x9403, "WaterDepth", 0x9404, "Acceleration",
            0x9405, "CameraElevationAngle", 0x9C9B, "XPTitle", 0x9C9C, "XPComment", 0x9C9D, "XPAuthor",
            0x9C9E, "XPKeywords", 0x9C9F, "XPSubject", 0xA000, "FlashpixVersion", 0xA001, "ColorSpace",
            0xA002, "PixelXDimension", 0xA003, "PixelYDimension", 0xA004, "RelatedSoundFile", 0xA20B, "FlashEnergy",
            0xA20E, "FocalPlaneXResolution", 0xA20F, "FocalPlaneYResolution", 0xA210, "FocalPlaneResolutionUnit",
            0xA214, "SubjectLocation", 0xA215, "ExposureIndex", 0xA217, "SensingMethod", 0xA300, "FileSource",
            0xA301, "SceneType", 0xA302, "CFAPattern", 0xA401, "CustomRendered", 0xA402, "ExposureMode",
            0xA403, "WhiteBalance", 0xA404, "DigitalZoomRatio", 0xA405, "FocalLengthIn35mmFilm",
            0xA406, "SceneCaptureType", 0xA407, "GainControl", 0xA408, "Contrast", 0xA409, "Saturation",
            0xA40A, "Sharpness", 0xA40B, "DeviceSettingDescription", 0xA40C, "SubjectDistanceRange",
            0xA420, "ImageUniqueID", 0xA430, "CameraOwnerName", 0xA431, "BodySerialNumber",
            0xA432, "LensSpecification", 0xA433, "LensMake", 0xA434, "LensModel", 0xA435, "LensSerialNumber",
            0xA460, "CompositeImage", 0xA500, "Gamma", 0xC4A5, "PrintImageMatching");

    private static final Map<Integer, String> GPS_TAGS = tags(
            0, "GPSVersionID", 1, "GPSLatitudeRef", 2, "GPSLatitude", 3, "GPSLongitudeRef", 4, "GPSLongitude",
            5, "GPSAltitudeRef", 6, "GPSAltitude", 7, "GPSTimeStamp", 8, "GPSSatellites", 9, "GPSStatus",
            10, "GPSMeasureMode", 11, "GPSDOP", 12, "GPSSpeedRef", 13, "GPSSpeed", 14, "GPSTrackRef", 15, "GPSTrack",
            16, "GPSImgDirectionRef", 17, "GPSImgDirection", 18, "GPSMapDatum", 19, "GPSDestLatitudeRef",
            20, "GPSDestLatitude", 21, "GPSDestLongitudeRef", 22, "GPSDestLongitude", 23, "GPSDestBearingRef",
            24, "GPSDestBearing", 25, "GPSDestDistanceRef", 26, "GPSDestDistance", 27, "GPSProcessingMethod",
            28, "GPSAreaInformation", 29, "GPSDateStamp", 30, "GPSDifferential", 31, "GPSHPositioningError");

    private static final Map<Integer, String> INTEROP_TAGS = tags(
            1, "InteroperabilityIndex", 2, "InteroperabilityVersion", 0x1000, "RelatedImageFileFormat",
            0x1001, "RelatedImageWidth", 0x1002, "RelatedImageLength");

    // ── IPTC ──

    private static final Map<Integer, String> IPTC_NAMES = tags(
            0, "Record Version", 3, "Object Type", 4, "Object Attribute", 5, "Object Name", 7, "Edit Status",
            10, "Urgency", 12, "Subject Reference", 15, "Category", 20, "Supplemental Category",
            22, "Fixture Identifier", 25, "Keywords", 26, "Content Location Code", 27, "Content Location Name",
            30, "Release Date", 35, "Release Time", 37, "Expiration Date", 38, "Expiration Time",
            40, "Special Instructions", 42, "Action Advised", 45, "Reference Service", 47, "Reference Date",
            50, "Reference Number", 55, "Date Created", 60, "Time Created", 62, "Digital Creation Date",
            63, "Digital Creation Time", 65, "Originating Program", 70, "Program Version", 75, "Object Cycle",
            80, "By-line", 85, "By-line Title", 90, "City", 92, "Sub-location", 95, "Province/State",
            100, "Country Code", 101, "Country", 103, "Original Transmission Reference", 105, "Headline",
            110, "Credit", 115, "Source", 116, "Copyright Notice", 118, "Contact", 120, "Caption/Abstract",
            122, "Writer/Editor", 130, "Image Type", 131, "Image Orientation", 135, "Language Identifier");

    /** IPTC-IIM datasets: 0x1C, record, dataset, length, data. */
    private static void iptc(byte[] d, int p, int end, Out out) {
        var lines = new ArrayList<String>();
        while (p + 5 <= end && d[p] == 0x1C) {
            int record = d[p + 1] & 0xFF, dataset = d[p + 2] & 0xFF;
            long length = u16(d, p + 3, false);
            int start = p + 5;
            if ((length & 0x8000) != 0) {
                int n = (int) (length & 0x7FFF);
                if (n > 4 || start + n > end) break;
                length = 0;
                for (int i = 0; i < n; i++) length = length << 8 | (d[start + i] & 0xFF);
                start += n;
            }
            if (length > end - start) {
                lines.add("(A part runs past its end; it was not read.)");
                break;
            }
            int stop = start + (int) length;
            String name = record == 2 ? IPTC_NAMES.get(dataset) : null;
            if (record == 1 && dataset == 90) name = "Coded Character Set";
            if (name == null) name = "Dataset " + record + ":" + dataset;
            String said = textOrNull(d, start, stop);
            lines.add(name + ": " + (said != null ? said : stop - start <= 16 ? hex(d, start, stop) : count(stop - start) + " of binary data"));
            p = stop;
        }
        if (p < end && lines.isEmpty()) lines.add(count(end - p) + " in no form this reads");
        if (!lines.isEmpty()) out.block("IPTC:", String.join("\n", lines));
    }

    // ── Colour profiles ──

    /** A colour profile, named as its file names it and as its own description does, and sized. */
    private static String colourProfile(String name, byte[] profile, long size) {
        String described = profile == null ? null : iccDescription(profile);
        var sb = new StringBuilder("A colour profile");
        if (!name.isBlank()) sb.append(" named \"").append(name).append('"');
        if (described != null && !described.isBlank() && !described.equals(name)) {
            sb.append(name.isBlank() ? " " : ", ").append("described as \"").append(described).append('"');
        }
        return sb.append(", ").append(count(profile != null ? profile.length : size)).append('.').toString();
    }

    /** An ICC profile's description tag: the v2 'desc' or the v4 'mluc' form; null when it has none. */
    static String iccDescription(byte[] p) {
        try {
            if (p.length < 132) return null;
            long tags = u32(p, 128, false);
            for (long i = 0; i < tags && 132 + 12 * i + 12 <= p.length; i++) {
                int e = (int) (132 + 12 * i);
                if (!latin1(p, e, e + 4).equals("desc")) continue;
                long off = u32(p, e + 4, false), size = u32(p, e + 8, false);
                if (off + size > p.length || size < 12) return null;
                int o = (int) off;
                String type = latin1(p, o, o + 4);
                if (type.equals("desc")) {
                    long n = u32(p, o + 8, false);
                    if (12 + n > size) return null;
                    return trimmed(latin1(p, o + 12, (int) (o + 12 + n)));
                }
                if (type.equals("mluc") && size >= 28) {
                    long length = u32(p, o + 20, false), at = u32(p, o + 24, false);
                    if (at + length > size) return null;
                    return trimmed(new String(p, (int) (o + at), (int) length, StandardCharsets.UTF_16BE));
                }
                return null;
            }
        } catch (RuntimeException broken) {
            return null;
        }
        return null;
    }

    // ── Words ──

    /** What is said, part by part. */
    private static final class Out {
        private final StringBuilder sb = new StringBuilder();
        private final long unpackLimit;
        private long unpacked;

        Out(long unpackLimit) {
            this.unpackLimit = unpackLimit;
        }

        boolean isEmpty() {
            return sb.isEmpty();
        }

        String text() {
            return sb.toString();
        }

        void section(String line) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append(line).append('\n');
        }

        void block(String heading, String body) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append(heading).append('\n').append(body.strip()).append('\n');
        }

        /** A part of another program's: its text when it is text, else what it is and its size. */
        void part(String what, byte[] d, int from, int to) {
            String text = textOrNull(d, from, to);
            if (text != null && !text.isBlank()) block(what + ", as text:", text);
            else section(what + ", " + count(to - from) + " of binary data.");
        }

        /**
         * {@code d} from {@code from} to {@code to}, zlib-inflated: null, said why, when it is broken
         * or would take the file past {@link #unpackLimit} in all.
         */
        byte[] unpack(byte[] d, int from, int to, String what) {
            var inflater = new java.util.zip.Inflater();
            try {
                inflater.setInput(d, from, to - from);
                var all = new ByteArrayOutputStream();
                byte[] buffer = new byte[1 << 16];
                while (!inflater.finished()) {
                    int got = inflater.inflate(buffer);
                    if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                    unpacked += got;
                    if (unpacked > unpackLimit) {
                        section("Unpacked, " + what + " takes the file's text past " + count(unpackLimit)
                                + " -- more than an upload may be -- so it was not unpacked here; a skill can read the file.");
                        return null;
                    }
                    all.write(buffer, 0, got);
                }
                return all.toByteArray();
            } catch (java.util.zip.DataFormatException broken) {
                section(capitalized(what) + " is broken and could not be unpacked.");
                return null;
            } finally {
                inflater.end();
            }
        }
    }

    private static String capitalized(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** "1 byte", "3,144 bytes". */
    static String count(long bytes) {
        return String.format(Locale.ROOT, "%,d %s", bytes, bytes == 1 ? "byte" : "bytes");
    }

    private static String hex(byte[] d, int from, int to) {
        var sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) sb.append(' ');
            sb.append(String.format(Locale.ROOT, "%02x", d[i] & 0xFF));
        }
        return sb.toString();
    }

    /** Text as a file writes it: UTF-8 when it is that, else Latin-1; NULs at its end dropped, those inside a line break. */
    static String text(byte[] d, int from, int to) {
        String s = strictUtf8(d, from, to);
        return trimmed(s != null ? s : latin1(d, from, to));
    }

    /** {@link #text} when the bytes are UTF-8 text -- no control characters but tabs and line breaks; else null. */
    static String textOrNull(byte[] d, int from, int to) {
        String s = strictUtf8(d, from, to);
        if (s == null) return null;
        s = trimmed(s);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r' || c == 0x7F) return null;
        }
        return s;
    }

    private static String strictUtf8(byte[] d, int from, int to) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(d, from, to - from)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static String trimmed(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == 0) end--;
        return s.substring(0, end).replace('\0', '\n');
    }

    private static String utf8(byte[] d, int from, int to) {
        return to <= from ? "" : new String(d, from, to - from, StandardCharsets.UTF_8);
    }

    private static String latin1(byte[] d, int from, int to) {
        return to <= from ? "" : new String(d, from, to - from, StandardCharsets.ISO_8859_1);
    }

    /** A chunk type or a program's name as it can be quoted: its printable ASCII, the rest as "?". */
    private static String printable(String s) {
        var sb = new StringBuilder();
        for (char c : s.toCharArray()) sb.append(c >= 0x20 && c < 0x7F && c != '"' ? c : '?');
        return sb.toString();
    }

    private static int indexOf(byte[] d, int from, int to, byte b) {
        for (int i = from; i < to; i++) if (d[i] == b) return i;
        return -1;
    }

    private static boolean starts(byte[] d, int at, String prefix) {
        return starts(d, at, prefix.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static boolean starts(byte[] d, int at, byte[] prefix) {
        if (at < 0 || d.length - at < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (d[at + i] != prefix[i]) return false;
        return true;
    }

    private static int u16(byte[] d, int at, boolean little) {
        return little ? (d[at] & 0xFF) | (d[at + 1] & 0xFF) << 8 : (d[at] & 0xFF) << 8 | (d[at + 1] & 0xFF);
    }

    private static long u32(byte[] d, int at, boolean little) {
        return little
                ? (d[at] & 0xFFL) | (d[at + 1] & 0xFFL) << 8 | (d[at + 2] & 0xFFL) << 16 | (d[at + 3] & 0xFFL) << 24
                : (d[at] & 0xFFL) << 24 | (d[at + 1] & 0xFFL) << 16 | (d[at + 2] & 0xFFL) << 8 | (d[at + 3] & 0xFFL);
    }
}
