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
 * the assistant turns, which replay actions already taken (see CloudGateway). That is what
 * makes privacy a property of the code path rather than of a prompt builder's carefulness: the
 * builders can be wrong about what they rendered and this still refuses the call. The task asks
 * the same question of every result before labelling it ({@link #firstLeakInResult}, through
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
 * indexed, plus whole strings registered as such: one of 8 to 31 characters, or a longer one
 * whose every window is filler). It is a check on the renderers, not a semantic leak detector.
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
        return firstLeak(normalise(part), (handle, stretch) -> false);
    }

    /**
     * The earliest run in {@code text} that belongs to a registered artifact and that
     * {@code excused} does not excuse, or null -- what the canary refuses to send.
     * <p>
     * Every run is tried, not only the first: one excused run does not clear the text. A private
     * confirmation may open with a run of the public thing it was given and continue with an
     * address and a message id that are nobody else's -- shorter than a window, and caught by the
     * windows that take in the end of the one and the start of the other.
     * <p>
     * The text is normalised once and read once, with a rolling hash for every registered length
     * moving forward together, and at each offset the one run that counts there is taken: the
     * longest registration that starts there, and of the windows, the first registered
     * artifact's. Runs of one artifact at consecutive offsets form a stretch, and
     * {@code excused} is asked about the whole stretch first, so text the cloud was given in one
     * piece is one question however long it is. Only a stretch it was not given is halved, down
     * to the window that is the leak. Asked window by window, with the scan started again after
     * each, a 24,000-character public digest that a private confirmation quoted took eleven
     * seconds to label, and the door paid as much again on every think call that sent it.
     * <p>
     * What {@code excused} is asked about is stripped of the whitespace at its two ends. A
     * result is sent on lines of its own ({@code AgentTrajectory.Turn.observationText}), and
     * normalised, a line break is a space: a window that starts at the one before a result and
     * runs on into text the cloud was given adds nothing to that text but the space, yet the
     * source the cloud was given it in starts with the text. Every other character counts: a
     * window that joins an excused run to a private one is the leak described above.
     *
     * @param excused whether the cloud was already given the whole of a stretch of normalised
     *                text (handle, stretch) -- yes only if it would say yes to every window in it
     */
    public Hit firstLeakIn(String text, BiPredicate<Integer, String> excused) {
        return firstLeak(normalise(text), excused);
    }

    /**
     * {@link #firstLeakIn}, asked of a result as the think prompts send it: on lines of its own
     * ({@code AgentTrajectory.Turn.observationText}). Normalised, the line breaks around it are
     * spaces, and a space can complete a window -- a result that opens with 31 characters which a
     * private result has after a space is, once rendered, a whole window of it. Scanned with a
     * space at each end, a result shows the label ({@code AgentContext.decide}) every window of
     * it the door will see. Offsets count the leading space.
     */
    public Hit firstLeakInResult(String output, BiPredicate<Integer, String> excused) {
        return firstLeak(" " + normalise(output) + " ", excused);
    }

    /** The one scan, over text already normalised. */
    private Hit firstLeak(String n, BiPredicate<Integer, String> excused) {
        // Every registered length that fits in the text, each with the hash of the text's run of
        // that length that starts at the current offset: WINDOW for the windows, and each whole
        // string's own length -- under WINDOW, or over it when every window of it was filler.
        int[] lengths = new int[shortLengths.length + 1];
        int slots = 0;
        int windowSlot = -1;
        if (handles.length > 0 && WINDOW <= n.length()) {
            windowSlot = slots;
            lengths[slots++] = WINDOW;
        }
        int[] slotOf = new int[shortLengths.length];   // each whole string's slot, or -1
        for (int k = 0; k < shortLengths.length; k++) {
            int slot = -1;
            if (shortLengths[k] <= n.length()) {
                for (int s = 0; s < slots && slot < 0; s++) {
                    if (lengths[s] == shortLengths[k]) slot = s;
                }
                if (slot < 0) {
                    slot = slots;
                    lengths[slots++] = shortLengths[k];
                }
            }
            slotOf[k] = slot;
        }
        if (slots == 0) return null;
        long[] hash = new long[slots];
        long[] lead = new long[slots];   // BASE^(length-1): the weight of the character leaving
        int shortest = Integer.MAX_VALUE;
        for (int s = 0; s < slots; s++) {
            for (int i = 0; i < lengths[s]; i++) hash[s] = hash[s] * BASE + n.charAt(i);
            lead[s] = pow(BASE, lengths[s] - 1);
            shortest = Math.min(shortest, lengths[s]);
        }

        int from = -1, to = -1, handle = 0, length = 0;   // the stretch being gathered
        for (int at = 0; at + shortest <= n.length(); at++) {
            int hitHandle = 0, hitLength = 0;
            if (windowSlot >= 0 && at + WINDOW <= n.length()) {
                for (int k = 0; k < handles.length; k++) {
                    if (Arrays.binarySearch(windowHashes[k], hash[windowSlot]) >= 0) {
                        hitHandle = handles[k];
                        hitLength = WINDOW;
                        break;
                    }
                }
            }
            // At the same offset the LONGER registration wins. "ORDER-4471" and
            // "ORDER-4471 petr@example.com" both start at the same place; allowing the short one
            // must not hide the long one, which is the specific thing.
            for (int k = 0; k < shortHashes.length; k++) {
                int len = shortLengths[k];
                if (slotOf[k] >= 0 && len > hitLength && at + len <= n.length()
                        && hash[slotOf[k]] == shortHashes[k]) {
                    hitHandle = shortHandles[k];
                    hitLength = len;
                }
            }

            boolean continues = from >= 0 && at == to + 1
                    && hitHandle == handle && hitLength == length;
            if (hitLength > 0 && continues) {
                to = at;
            } else {
                if (from >= 0) {
                    Hit leak = firstUnexcused(n, from, to, handle, length, excused);
                    if (leak != null) return leak;
                }
                from = hitLength > 0 ? at : -1;
                to = at;
                handle = hitHandle;
                length = hitLength;
            }

            for (int s = 0; s < slots; s++) {
                if (at + lengths[s] < n.length()) {
                    hash[s] = (hash[s] - n.charAt(at) * lead[s]) * BASE + n.charAt(at + lengths[s]);
                }
            }
        }
        return from >= 0 ? firstUnexcused(n, from, to, handle, length, excused) : null;
    }

    /**
     * The first window of the stretch of runs at offsets {@code from..to} that {@code excused}
     * does not excuse, or null: the whole stretch asked first, then each half.
     */
    private static Hit firstUnexcused(String n, int from, int to, int handle, int length,
                                      BiPredicate<Integer, String> excused) {
        if (excused.test(handle, n.substring(from, to + length).strip())) return null;
        if (from == to) return new Hit(handle, from, length);
        int mid = (from + to) >>> 1;
        Hit leak = firstUnexcused(n, from, mid, handle, length, excused);
        return leak != null ? leak : firstUnexcused(n, mid + 1, to, handle, length, excused);
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
