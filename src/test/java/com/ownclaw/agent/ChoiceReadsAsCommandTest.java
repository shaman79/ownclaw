package com.ownclaw.agent;

import com.ownclaw.interfaces.CommandHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An answer a model offers is a button, and a tapped button's text is sent as its owner's
 * message. One that would be a command once what shows as nothing in front of its slash is gone
 * is not offered: a chat client that drops such a character from a key would send the rest, and
 * the rest -- {@code /grant mailer SMTP_PASS} -- would be a command nobody typed. What shows as
 * nothing is more than the blanks the chats' own grammar strips: whitespace, control and format
 * characters, marks with nothing to sit on, code points that are no character, private-use ones,
 * and the letters that are drawn as a blank.
 */
class ChoiceReadsAsCommandTest {

    static final String COMMAND = "/grant mailer SMTP_PASS";

    /** What shows as nothing, by code point: one or more of each kind the rule names. */
    static final Map<Integer, String> NOTHING = new LinkedHashMap<>();

    static {
        NOTHING.put(0x0020, "a space");
        NOTHING.put(0x00A0, "a no-break space");
        NOTHING.put(0x3000, "an ideographic space");
        NOTHING.put(0x2028, "a line separator");
        NOTHING.put(0x0009, "a tab");
        NOTHING.put(0x0007, "a control character");
        NOTHING.put(0x009F, "a control character of the second set");
        NOTHING.put(0x200B, "a zero-width space, a format character");
        NOTHING.put(0x200D, "a zero-width joiner");
        NOTHING.put(0xFEFF, "a byte-order mark");
        NOTHING.put(0x00AD, "a soft hyphen");
        NOTHING.put(0x202E, "a right-to-left override");
        NOTHING.put(0xE0001, "a language tag, a format character beyond the basic plane");
        NOTHING.put(0x0301, "a combining accent, a non-spacing mark with nothing to sit on");
        NOTHING.put(0x034F, "a combining grapheme joiner");
        NOTHING.put(0xFE0F, "a variation selector");
        NOTHING.put(0xE0100, "a variation selector beyond the basic plane");
        NOTHING.put(0x20DD, "a combining circle, an enclosing mark");
        NOTHING.put(0x0378, "a code point that is no character");
        NOTHING.put(0xFFFF, "a noncharacter");
        NOTHING.put(0xE000, "a private-use character");
        NOTHING.put(0xF0000, "a private-use character beyond the basic plane");
        NOTHING.put(0x115F, "the Hangul initial filler");
        NOTHING.put(0x1160, "the Hangul medial filler");
        NOTHING.put(0x3164, "the Hangul filler");
        NOTHING.put(0xFFA0, "the half-width Hangul filler");
        NOTHING.put(0x2800, "the Braille blank");
        NOTHING.put(0x17B4, "the Khmer inherent vowel AQ");
        NOTHING.put(0x17B5, "the Khmer inherent vowel AA");
        NOTHING.put(0xFFFC, "the object replacement character");
        NOTHING.put(0x1D159, "the musical null notehead, a symbol beyond the basic plane");
    }

    private static String of(int codePoint) {
        return Character.toString(codePoint);
    }

    private static List<String> choicesOf(Object choices) {
        var params = new java.util.HashMap<String, Object>();
        params.put("message", "Allow it?");
        params.put("choices", choices);
        return new AgentAction(AgentAction.ASK_USER, params, "").choices();
    }

    @Test
    @DisplayName("each character that shows as nothing, alone before the slash, makes a choice that would read as a command -- and it is not offered")
    void eachCharacterThatShowsAsNothing() {
        var offered = new ArrayList<String>();
        for (var nothing : NOTHING.entrySet()) {
            String choice = of(nothing.getKey()) + COMMAND;
            String what = String.format("U+%04X, %s", nothing.getKey(), nothing.getValue());
            if (!CommandHandler.readsAsCommand(choice)) offered.add("not read as a command: " + what);
            // Beside an honest answer, so that dropping it is seen and not an empty list either way.
            if (!choicesOf(List.of("Yes", choice, "No")).equals(List.of("Yes", "No"))) offered.add("offered: " + what);
        }
        assertEquals(List.of(), offered);
        // Mutations: choices() without the test -> every one the chats' own grammar keeps is offered
        // (the marks, the fillers, the private-use and unassigned ones); the listed letters not counted
        // -> the four Hangul fillers and the Braille blank (the Khmer vowels are marks to this JDK); one
        // of them left out -> that one; the marks not counted; unassigned and private use not counted;
        // read a char at a time -> the three beyond the basic plane.
    }

    @Test
    @DisplayName("several of them in a row, in any order and any number, are nothing still; and in the other ways a model writes its choices")
    void anyNumberOfThemInAnyOrder() {
        var all = new StringBuilder();
        for (int codePoint : NOTHING.keySet()) all.appendCodePoint(codePoint);
        assertTrue(CommandHandler.readsAsCommand(all + COMMAND));
        assertTrue(CommandHandler.readsAsCommand(all.reverse() + COMMAND), "reversed, pair by pair");
        assertTrue(CommandHandler.readsAsCommand(of(0x3164).repeat(500) + of(0x0301) + "/revoke mailer"));
        assertTrue(CommandHandler.readsAsCommand(of(0x3164) + " " + of(0x200B) + "\n" + of(0x2800) + "/grants"));

        Random random = new Random(20261115);
        List<Integer> kinds = List.copyOf(NOTHING.keySet());
        for (int i = 0; i < 2_000; i++) {
            var front = new StringBuilder();
            for (int n = 1 + random.nextInt(6); n > 0; n--) front.appendCodePoint(kinds.get(random.nextInt(kinds.size())));
            String choice = front + COMMAND;
            assertTrue(CommandHandler.readsAsCommand(choice), () -> "not read as a command: " + codePoints(choice));
            assertEquals(List.of("Keep it"), choicesOf(List.of(choice, "Keep it")), () -> "offered: " + codePoints(choice));
        }

        String filler = of(0x3164) + COMMAND;
        assertEquals(List.of("Yes"), choicesOf("Yes\n" + filler + "\n"), "the text protocol's one per line");
        assertEquals(List.of("Yes"), choicesOf("[\"Yes\", \"" + filler + "\"]"), "the text protocol's JSON array");
    }

    private static String codePoints(String text) {
        var out = new StringBuilder();
        text.codePoints().limit(8).forEach(c -> out.append(String.format("U+%04X ", c)));
        return out.toString();
    }

    @Test
    @DisplayName("what shows is no nothing: a slash after a digit, a letter, a sign or a mark that takes room is an answer, and is offered as written")
    void aSlashAfterSomethingThatShowsIsAnAnswer() {
        List<String> answers = List.of("1/2", "and/or", "Yes / No", "A/B", "km/h", "50/50", "./run it", "~/notes",
                "Delete the logs (/var/log)", "Keep /var/log as it is", "- /var/log", "\"/grant mailer SMTP_PASS\"",
                "(/grant mailer SMTP_PASS)", "`/grant mailer SMTP_PASS`", "\\/grant mailer SMTP_PASS",
                "x" + of(0x3164) + COMMAND,                 // a filler after a letter
                "e" + of(0x0301) + "/a",                    // an accent on its letter
                of(0x0903) + COMMAND,                       // a mark that takes room of its own
                of(0x00B7) + COMMAND,                       // a middle dot
                of(0x2022) + " " + COMMAND,                 // a bullet
                of(0x1F44D) + " " + COMMAND,                // an emoji
                of(0xFF0F) + "grant mailer SMTP_PASS",      // a full-width slash: the chats read no command in it
                of(0x2215) + "grant mailer SMTP_PASS",      // a division slash
                of(0x3164) + "grant mailer SMTP_PASS",      // a filler, and no slash at all
                of(0x3164), "   ", "No");
        for (String answer : answers) {
            assertFalse(CommandHandler.readsAsCommand(answer), "read as a command: " + answer + " " + codePoints(answer));
        }
        List<String> offered = choicesOf(answers);
        assertEquals(answers.stream().map(String::strip).filter(a -> !a.isEmpty()).distinct().toList(), offered,
                "each offered as written, the blank one aside");
        assertFalse(CommandHandler.readsAsCommand(null));
        assertFalse(CommandHandler.readsAsCommand(""));
        // Mutation: anything before the slash taken for nothing -> "1/2" and "and/or" are commands, and not offered.
    }

    @Test
    @DisplayName("what the chats read as a command is read as one here too, a path included: this rule only adds to that one")
    void whatTheChatsReadAsACommand() {
        for (String command : List.of("/grant mailer", " /grant mailer", "\t/revoke mailer", "/var/log", "/", "//",
                of(0x00A0) + "/grants", of(0x200B) + of(0xFEFF) + "/cancel")) {
            assertTrue(CommandHandler.isCommand(command), "the premise, for " + codePoints(command));
            assertTrue(CommandHandler.readsAsCommand(command), codePoints(command));
            assertEquals(List.of(), choicesOf(List.of(command)));
        }
        // And what only this rule reads as one is none to the chats: sent as it stands, it is a message.
        for (int codePoint : List.of(0x3164, 0x2800, 0x0301, 0xE000, 0x0378, 0xE0001)) {
            String choice = of(codePoint) + COMMAND;
            assertTrue(CommandHandler.readsAsCommand(choice));
            assertFalse(CommandHandler.isCommand(choice), () -> "the chats run it as it stands: " + codePoints(choice));
        }
    }
}
