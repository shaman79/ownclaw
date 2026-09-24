package com.ownclaw.privacy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The canary is the one privacy mechanism that does not trust the prompt builders.
 * <p>
 * Every other guard in Phase 3 shapes what the builders put into a prompt; this one checks what
 * they produced, byte-wise, before it leaves. So its own behaviour has to be pinned harder than
 * anything upstream of it: what it catches, what it deliberately does not, and that it keeps no
 * copy of the text it is protecting.
 */
class PrivateIndexTest {

    /** Distinctive prose: no repeated windows, plenty of distinct characters. */
    private static String prose(int chars, long seed) {
        var r = new Random(seed);
        var sb = new StringBuilder(chars + 16);
        String[] words = {"ledger", "invoice", "kancelář", "platba", "Bratčice", "quarterly",
                "IBAN", "CZ6508000000192000145399", "due", "2026-09-23", "Novák", "gulášovka"};
        while (sb.length() < chars) {
            sb.append(words[r.nextInt(words.length)]).append(r.nextInt(10_000)).append(' ');
        }
        return sb.substring(0, chars);
    }

    @Test
    @DisplayName("a 40-character run of a private text is found, however it was re-cased or re-wrapped")
    void longRunIsFound() {
        String secret = prose(5_000, 1);
        var idx = new PrivateIndex();
        idx.addPrivate(3, secret);

        String slice = secret.substring(2_000, 2_040);
        String disguised = "Here is what I found:\n\n  " + slice.toUpperCase()
                .replace(" ", "\n\t ") + "\n\nRegards";

        var hit = idx.firstHitIn(disguised);
        assertNotNull(hit, "casing and wrapping are the two things a renderer changes");
        assertEquals(3, hit.handle(), "and it names which artifact leaked");
    }

    @Test
    @DisplayName("a 20-character overlap with a long text is not a hit")
    void shortOverlapIsNotAHit() {
        String secret = prose(5_000, 2);
        var idx = new PrivateIndex();
        idx.addPrivate(1, secret);

        assertNull(idx.firstHitIn("quote: " + secret.substring(1_000, 1_020) + " end"),
                "below the window a coincidence is more likely than a leak; a balance, a name "
                        + "or a date inside a long artifact is deliberately not protected here");
    }

    @Test
    @DisplayName("a short private string is caught whole; a very short one is ignored")
    void shortStringsAreWholeStringMatches() {
        var idx = new PrivateIndex();
        idx.addPrivate(2, "Sent, message id 42");   // 19 chars: the smtp confirmation
        idx.addPrivate(4, "ok=1");                    // 4 chars: nothing worth protecting

        var hit = idx.firstHitIn("The tool returned: sent, MESSAGE id 42.");
        assertNotNull(hit, "a 32-char window never fires on a 19-char confirmation, so short "
                + "registrations are matched as whole strings");
        assertEquals(2, hit.handle());
        assertNull(idx.firstHitIn("status ok=1 today"), "four characters is not a secret");
    }

    @Test
    @DisplayName("filler is not data: forty dashes registered and forty dashes sent is not a hit")
    void lowEntropyWindowsAreSkipped() {
        var idx = new PrivateIndex();
        idx.addPrivate(5, "─".repeat(40) + "\n" + "=".repeat(40));

        assertNull(idx.firstHitIn("═══════════════════════════════════════\n" + "─".repeat(40)),
                "a rule line in a menu is not the menu");
    }

    @Test
    @DisplayName("the index keeps no text: no String, CharSequence, char[] or collection field")
    void keepsNoCopyOfTheText() {
        for (Field f : PrivateIndex.class.getDeclaredFields()) {
            Class<?> t = f.getType();
            assertFalse(CharSequence.class.isAssignableFrom(t), f.getName());
            assertFalse(t == char[].class, f.getName());
            assertFalse(Collection.class.isAssignableFrom(t), f.getName());
            assertFalse(Map.class.isAssignableFrom(t), f.getName());
        }
        // Not a promise: the roadmap's warning was that an audit copy of private content is the
        // largest new sensitive file set on disk. This class is hashes and integers.
    }

    @Test
    @DisplayName("the earliest hit wins, and a hit from a later artifact does not hide an earlier offset")
    void earliestOffset() {
        String a = prose(200, 7);
        var idx = new PrivateIndex();
        idx.addPrivate(1, a);
        idx.addPrivate(2, "Sent, message id 42");

        String part = "sent, message id 42 — then: " + a.substring(0, 60);
        var hit = idx.firstHitIn(part);
        assertNotNull(hit);
        assertEquals(2, hit.handle());
        assertEquals(0, hit.offset());
    }

    @Test
    @DisplayName("a very large artifact is sampled but a 47-character run still hits")
    void largeTextsAreStridedButStillCaught() {
        String big = prose(PrivateIndex.STRIDE_ABOVE + 10_000, 9);
        var idx = new PrivateIndex();
        idx.addPrivate(8, big);

        int at = 1_234_567;
        assertNotNull(idx.firstHitIn("…" + big.substring(at, at + 47) + "…"),
                "at stride 16 a run of 47 characters always covers one indexed window");
    }

    @Test
    @DisplayName("at one offset the longest registration wins, so allowing a prefix hides nothing")
    void theLongestRegistrationAtAnOffsetWins() {
        // An order number is registered on its own, and again inside the line that carries the
        // customer's address. Both start at the same character. If the scan reports the short
        // one and the caller allows it, the scan must not have moved past the long one — the
        // long one is the specific thing, and it is nobody's to send.
        var idx = new PrivateIndex();
        idx.addPrivate(1, "ORDER-4471/BQ");
        idx.addPrivate(2, "ORDER-4471/BQ petr@example.com");

        var hit = idx.firstHitIn("see ORDER-4471/BQ petr@example.com now");
        assertNotNull(hit);
        assertEquals(2, hit.handle(),
                "the earliest offset is the same for both; length breaks the tie");
        assertEquals(30, hit.length());
    }

    @Test
    @DisplayName("repetitive is not filler: card numbers and dates are still indexed")
    void repetitiveDataIsNotTreatedAsFiller() {
        // Every 32-char window of these fails the distinct-character test, so the artifact was
        // dropped from the index entirely and the bytes went to the cloud in any part, with no
        // collision needed. Filler is a row of dashes; a pair of card numbers is the data.
        for (String data : java.util.List.of("4111 1111 1111 1111 4111 1111 1111 1111",
                                   "2026-10-01 2026-10-02 2026-10-03",
                                   "+420 111 222 111 222 111 222 111")) {
            var idx = new PrivateIndex();
            idx.addPrivate(6, data);
            assertFalse(idx.isEmpty(), "not indexed at all: " + data);
            assertNotNull(idx.firstHitIn("the statement shows " + data + " twice"),
                    "quoted verbatim and not caught: " + data);
        }

        var filler = new PrivateIndex();
        filler.addPrivate(7, "-".repeat(40));
        assertTrue(filler.isEmpty(), "a row of dashes really is filler and must not refuse");
    }

    @Test
    @DisplayName("normalisation folds case, compatibility forms and whitespace runs")
    void normalisation() {
        assertEquals("kancelář novák 42", PrivateIndex.normalise("  Kancelář\n\tNOVÁK   42 "));
        assertEquals("", PrivateIndex.normalise(null));
    }
}
