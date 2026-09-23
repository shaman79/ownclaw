package com.ownclaw.privacy;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;

/**
 * The canary: can this text contain a run of any PRIVATE artifact's bytes?
 * <p>
 * Every PRIVATE artifact of a task is indexed here as hashes of its 32-character windows, and
 * every outbound cloud body is checked against them before the socket opens. That is what
 * makes privacy a property of the code path rather than of a prompt builder's carefulness: the
 * builders can be wrong about what they rendered and this still refuses the call.
 * <p>
 * It holds hashes and integers only — no field of type String, CharSequence, char[] or any
 * collection of them, and a test pins that by reflection. The roadmap's warning was that an
 * audit copy of private content is the largest new sensitive file set on disk; this is not one,
 * in memory or anywhere else. The cost is that a hash collision cannot be disproved here: the
 * caller may verify a hit against the artifact bytes it holds, and if it cannot, a collision
 * refuses a call. It can never let one through.
 * <p>
 * What it does not catch, stated plainly: a paraphrase; a fact shorter than eight characters;
 * a short value quoted from inside a long artifact (only the artifact's 32-character runs are
 * indexed, plus whole strings of 8 to 31 characters registered as such). It is a check on the
 * renderers, not a semantic leak detector.
 */
public final class PrivateIndex {

    /** Window width for long texts. Below this, a run is too short to be someone's data. */
    public static final int WINDOW = 32;
    /** Whole strings from this length up are registered as they are. */
    public static final int MIN_SHORT = 8;
    /** A window with fewer distinct characters than this is filler (dashes, dots), not data. */
    static final int MIN_DISTINCT = 8;
    /** Above this many characters the windows are sampled; a run of 47+ still hits. */
    static final int STRIDE_ABOVE = 2 * 1024 * 1024;
    static final int STRIDE = 16;

    private static final long BASE = 1_000_003L;
    // The term to remove when a character leaves a window of WINDOW chars is c * BASE^WINDOW —
    // the first version used BASE^(WINDOW-1), so every hash after the first window was wrong and
    // only a run at offset 0 could ever match. The tests caught it; the mutation would not have.
    private static final long BASE_POW_WINDOW = pow(BASE, WINDOW);

    /** A match: which artifact, and where in the NORMALISED text it was found. */
    public record Hit(int handle, int offset, int length) {}

    // Parallel primitive arrays: handles[i] owns windowHashes[i] (sorted, deduplicated).
    private int[] handles = new int[0];
    private long[][] windowHashes = new long[0][];

    // Whole-string registrations of 8..31 characters: hash, its length, and its handle.
    private long[] shortHashes = new long[0];
    private int[] shortLengths = new int[0];
    private int[] shortHandles = new int[0];

    /** How every text is seen — indexed and checked alike — so casing and wrapping cannot hide a run. */
    public static String normalise(String text) {
        if (text == null || text.isEmpty()) return "";
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        var sb = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!space) sb.append(' ');
                space = true;
            } else {
                sb.append(c);
                space = false;
            }
        }
        return sb.toString().strip();
    }

    /** Register an artifact's text under its handle. Nothing of the text is kept. */
    public void addPrivate(int handle, String text) {
        String n = normalise(text);
        if (n.length() < MIN_SHORT) return;
        if (n.length() < WINDOW) {
            addShort(handle, n);
            return;
        }
        int stride = n.length() > STRIDE_ABOVE ? STRIDE : 1;
        int windows = n.length() - WINDOW + 1;
        long[] hashes = new long[windows / stride + 1];
        int count = 0;
        long h = 0;
        for (int i = 0; i < n.length(); i++) {
            h = h * BASE + n.charAt(i);
            if (i >= WINDOW) h -= n.charAt(i - WINDOW) * BASE_POW_WINDOW;
            int start = i - WINDOW + 1;
            if (start < 0 || start % stride != 0) continue;
            if (distinct(n, start) < MIN_DISTINCT) continue;
            hashes[count++] = h;
        }
        if (count == 0) return;
        long[] sorted = Arrays.copyOf(hashes, count);
        Arrays.sort(sorted);
        handles = Arrays.copyOf(handles, handles.length + 1);
        windowHashes = Arrays.copyOf(windowHashes, windowHashes.length + 1);
        handles[handles.length - 1] = handle;
        windowHashes[windowHashes.length - 1] = sorted;
    }

    /** The earliest run in {@code part} that belongs to a registered artifact, or null. */
    public Hit firstHitIn(String part) {
        return firstHitIn(part, 0);
    }

    /**
     * The earliest run at or after {@code from} (an offset into the NORMALISED text).
     * <p>
     * The caller needs this because one allowed hit does not clear a part: a private
     * confirmation may open with a run of the public thing it was given and continue with an
     * address and a message id that are nobody else's.
     */
    public Hit firstHitIn(String part, int from) {
        String n = normalise(part);
        if (n.length() < MIN_SHORT) return null;
        Hit best = null;

        if (n.length() >= WINDOW && handles.length > 0) {
            long h = 0;
            for (int i = 0; i < n.length(); i++) {
                h = h * BASE + n.charAt(i);
                if (i >= WINDOW) h -= n.charAt(i - WINDOW) * BASE_POW_WINDOW;
                int start = i - WINDOW + 1;
                if (start < from) continue;
                for (int k = 0; k < handles.length; k++) {
                    if (Arrays.binarySearch(windowHashes[k], h) >= 0) {
                        best = new Hit(handles[k], start, WINDOW);
                        break;
                    }
                }
                if (best != null) break;
            }
        }

        for (int k = 0; k < shortHashes.length; k++) {
            int len = shortLengths[k];
            if (len > n.length()) continue;
            if (best != null && best.offset() == from) break;
            long pow = pow(BASE, len);
            long h = 0;
            for (int i = 0; i < n.length(); i++) {
                h = h * BASE + n.charAt(i);
                if (i >= len) h -= n.charAt(i - len) * pow;
                int start = i - len + 1;
                if (start < from) continue;
                if (best != null && start >= best.offset()) break;
                if (h == shortHashes[k]) {
                    best = new Hit(shortHandles[k], start, len);
                    break;
                }
            }
        }
        return best;
    }

    /** Whether anything at all is registered. */
    public boolean isEmpty() {
        return handles.length == 0 && shortHashes.length == 0;
    }

    private void addShort(int handle, String normalised) {
        long h = 0;
        for (int i = 0; i < normalised.length(); i++) h = h * BASE + normalised.charAt(i);
        shortHashes = Arrays.copyOf(shortHashes, shortHashes.length + 1);
        shortLengths = Arrays.copyOf(shortLengths, shortLengths.length + 1);
        shortHandles = Arrays.copyOf(shortHandles, shortHandles.length + 1);
        shortHashes[shortHashes.length - 1] = h;
        shortLengths[shortLengths.length - 1] = normalised.length();
        shortHandles[shortHandles.length - 1] = handle;
    }

    private static int distinct(String s, int start) {
        int seen = 0;
        long lowBits = 0;      // chars < 64 tracked in a bitmask, the rest counted exactly
        int[] others = null;
        for (int i = start; i < start + WINDOW; i++) {
            char c = s.charAt(i);
            if (c < 64) {
                if ((lowBits & (1L << c)) == 0) { lowBits |= 1L << c; seen++; }
            } else {
                if (others == null) others = new int[WINDOW];
                boolean dup = false;
                for (int j = 0; j < i - start; j++) if (others[j] == c) { dup = true; break; }
                others[i - start] = c;
                if (!dup) seen++;
            }
            if (seen >= MIN_DISTINCT) return seen;
        }
        return seen;
    }

    private static long pow(long base, int exp) {
        long r = 1;
        for (int i = 0; i < exp; i++) r *= base;
        return r;
    }
}
