package com.ownclaw.privacy;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.BiPredicate;

/**
 * The canary: can this text contain a run of any PRIVATE artifact's bytes?
 * <p>
 * Every PRIVATE artifact of a task is indexed here as hashes of its 32-character windows, and
 * every outbound cloud body is checked against them before the socket opens -- all of it but
 * the model's own replayed turns, which it wrote itself (see CloudGateway). That is what
 * makes privacy a property of the code path rather than of a prompt builder's carefulness: the
 * builders can be wrong about what they rendered and this still refuses the call. The task asks
 * the same question of every result before labelling it ({@link #firstLeakIn}, through
 * AgentContext), so a result that repeats a private one is labelled PRIVATE instead of being
 * refused at the door one step later.
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

    /**
     * Distinct characters a whole string needs before it is registered as a fallback. Far lower
     * than {@link #MIN_DISTINCT}, which judges one 32-character window: a run of dashes inside
     * prose is filler, but a 39-character string built from three characters is a pair of card
     * numbers.
     */
    private static final int MIN_DISTINCT_WHOLE = 3;
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
        if (count == 0) {
            // Every window was filler by the distinct-character test -- but "filler" and
            // "repetitive data" are not the same thing. Two card numbers, a row of dates or a
            // list of phone numbers has three or four distinct characters and is exactly what
            // must never leave. Register the whole string instead, so a verbatim quote is still
            // caught; forty dashes still has too few to bother with.
            if (distinctInWhole(n) >= MIN_DISTINCT_WHOLE) addShort(handle, n);
            return;
        }
        long[] sorted = Arrays.copyOf(hashes, count);
        Arrays.sort(sorted);
        handles = Arrays.copyOf(handles, handles.length + 1);
        windowHashes = Arrays.copyOf(windowHashes, windowHashes.length + 1);
        handles[handles.length - 1] = handle;
        windowHashes[windowHashes.length - 1] = sorted;
    }

    /** The earliest run in {@code part} that belongs to a registered artifact, or null. */
    public Hit firstHitIn(String part) {
        return firstHitInNormalised(normalise(part), 0);
    }

    /**
     * The earliest run in {@code text} that belongs to a registered artifact and that
     * {@code excused} does not excuse, or null -- what the canary refuses to send.
     * <p>
     * Every run is tried, not only the first: one excused run does not clear the text. A private
     * confirmation may open with a run of the public thing it was given and continue with an
     * address and a message id that are nobody else's.
     * <p>
     * The text is normalised once and each run is looked for from there. Handing the raw text
     * back in for every run re-normalised the whole of it each time, which is quadratic in the
     * number of runs: 7.8 seconds was measured on one 130 KB part.
     *
     * @param excused whether a run (handle, normalised window) is material the cloud was
     *                already given
     */
    public Hit firstLeakIn(String text, BiPredicate<Integer, String> excused) {
        String n = normalise(text);
        Hit hit;
        for (int from = 0; (hit = firstHitInNormalised(n, from)) != null; from = hit.offset() + 1) {
            String window = n.substring(hit.offset(), Math.min(hit.offset() + hit.length(), n.length()));
            if (!excused.test(hit.handle(), window)) return hit;
        }
        return null;
    }

    /** The earliest run at or after {@code from}, in text that is already normalised. */
    private Hit firstHitInNormalised(String normalised, int from) {
        String n = normalised;
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
            // This registration cannot improve on what we have -- the best is already at the
            // earliest possible offset and is at least as long. Skip THIS one, not the rest:
            // registrations are in insertion order, not sorted by length, so a later one may
            // still be longer at that same offset.
            if (best != null && best.offset() == from && best.length() >= len) continue;
            long pow = pow(BASE, len);
            long h = 0;
            for (int i = 0; i < n.length(); i++) {
                h = h * BASE + n.charAt(i);
                if (i >= len) h -= n.charAt(i - len) * pow;
                int start = i - len + 1;
                if (start < from) continue;
                // At the same offset the LONGER registration wins. "ORDER-4471" and
                // "ORDER-4471 petr@example.com" both start at the same place; allowing the
                // short one must not hide the long one, which is the specific thing.
                if (best != null && (start > best.offset()
                        || (start == best.offset() && len <= best.length()))) break;
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

    /** How many distinct characters the whole string has — the filler test, over all of it. */
    private static int distinctInWhole(String n) {
        var seen = new java.util.HashSet<Character>();
        for (int i = 0; i < n.length(); i++) {
            seen.add(n.charAt(i));
            if (seen.size() >= MIN_DISTINCT_WHOLE) return seen.size();
        }
        return seen.size();
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
