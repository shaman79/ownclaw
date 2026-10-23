package com.ownclaw.interfaces;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.users.AuthService;
import com.ownclaw.users.CredentialVault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One grammar: whatever {@link CommandHandler#handle} stores as a secret, {@link CommandHandler#displayed}
 * hides; a text that begins with a secret command shows none of the secret it was typed with, whether
 * or not it can be stored; and whatever the grammar does not read as a secret command is shown
 * exactly as sent. Checked as a property over spellings, spacing and case, because the gaps between
 * two readings of the same text are always in the spellings nobody listed.
 */
public class SecretCommandTest {

    final List<String> kept = new ArrayList<>();

    final CommandHandler commands = new CommandHandler(null, null, null, null,
            new CredentialVault(null) {
                @Override public void storeCredential(String userId, String key, String value) {
                    kept.add(value);
                }
            }, null, null, null,
            new AuthService(null, null, new OwnClawConfig()) {
                @Override public boolean isOwner(String userId) { return true; }
                @Override public synchronized String register(String name, String password, String by) {
                    kept.add(password);
                    return "jwt";
                }
            }, null, null, null, null, null, null);

    static final String[] HEADS = {"/cred", "/CRED", "/Cred", "/creds", "/cred@bot", "/user", "/USER", "/users"};
    static final String[] VERBS = {"set", "SET", "Set", "add", "ADD", "put", ""};
    static final String[] GAPS = {" ", "  ", "\t", " ", "\n", "   ", ""};
    static final String[] NAMES = {"OPENWRT_PASS", "bob", "k", ""};
    static final String[] SECRETS = {"Zq7-secret", "Zq7 two words", "Zq7\nnext-line", "Zq7 nbsp", ""};

    static String pick(Random r, String[] from) { return from[r.nextInt(from.length)]; }

    @Test
    @DisplayName("what handle() keeps as a secret, displayed() never shows, nor a secret typed into a secret command; anything else is shown as sent")
    void oneGrammar() {
        Random r = new Random(20260930);
        int secretForms = 0;
        for (int i = 0; i < 20_000; i++) {
            String typed = pick(r, SECRETS);
            String text = pick(r, HEADS) + pick(r, GAPS) + pick(r, VERBS) + pick(r, GAPS)
                    + pick(r, NAMES) + pick(r, GAPS) + typed + (r.nextBoolean() ? pick(r, GAPS) : "");
            kept.clear();
            var answer = commands.handle("owner", text);
            String shown = CommandHandler.displayed(text);
            if (CommandHandler.carriesSecret(text)) {
                secretForms++;
                assertTrue(answer.isPresent(), "a secret form is always a command: " + text);
                assertTrue(shown.endsWith(" ••••••") || shown.endsWith(" …"), "shown: " + shown);
                assertFalse(!typed.isEmpty() && shown.contains(typed), "typed " + typed + " and showed " + shown);
            } else {
                assertEquals(text, shown, "only a secret form is changed");
                assertTrue(kept.isEmpty(), "kept a secret the display would not hide: " + text);
            }
            for (String secret : kept) {
                assertFalse(shown.contains(secret), "kept " + secret + " and showed " + shown);
                assertTrue(answer.isPresent() && !answer.get().contains(secret), "the reply repeats it: " + answer);
            }
        }
        assertTrue(secretForms > 1_000, "the generator must reach the secret forms: " + secretForms);
    }

    @Test
    @DisplayName("reading a long text takes time in proportion to it, not to its square")
    void linearTime() {
        String spaces = " ".repeat(1_000_000);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            assertTrue(CommandHandler.carriesSecret("/cred set K" + spaces), "a key alone: nothing to store, and hidden");
            assertTrue(CommandHandler.carriesSecret("/cred set K " + "x".repeat(1_000_000) + spaces));
            assertFalse(CommandHandler.carriesSecret("/cred" + spaces + "set"));
        });
    }

    @Test
    @DisplayName("a secret command the grammar cannot read stores nothing, is answered with the usage, and shows only the command")
    void anUnreadableSecretCommandIsHidden() {
        for (String text : List.of("/cred set SMTP_PASS=Zq7-secret", "/cred set SMTP_PASS:Zq7-secret",
                "/cred set Zq7-secret", "/user add bob:Zq7-secret", "/USER  ADD\tbob:Zq7-secret")) {
            kept.clear();
            var answer = commands.handle("owner", text);
            assertTrue(answer.orElse("").startsWith("Usage"), text + " -> " + answer);
            assertTrue(kept.isEmpty(), "stored from " + text);
            assertTrue(CommandHandler.carriesSecret(text), "Telegram deletes it, as it deletes the form that works: " + text);
            String shown = CommandHandler.displayed(text);
            assertTrue(shown.endsWith(" …") && !shown.contains("Zq7"), text + " -> " + shown);
        }
        assertEquals("/cred set …", CommandHandler.displayed("/cred set SMTP_PASS=Zq7-secret"));
    }

    @Test
    @DisplayName("the spellings that used to fall out of /cred are stored now, without the spaces around them")
    void oldGapsAreClosed() {
        for (String text : List.of("/cred set K Zq7-a", "/CRED SET K Zq7-b", "/cred set K\tZq7-c",
                "/cred\tset K Zq7-d", "/cred set K Zq7-e \n")) {
            kept.clear();
            assertTrue(commands.handle("owner", text).orElse("").startsWith("✅"), text);
            assertEquals(1, kept.size(), text);
            assertTrue(kept.get(0).matches("Zq7-[a-e]"), "stored as typed, trimmed: <" + kept.get(0) + ">");
        }
        assertEquals("✅ Credential 'OPENWRT_PASS' stored (encrypted).",
                commands.handle("owner", "/cred set openwrt_pass Zq7-f").orElse(""), "the reply names the key as stored");
    }

    @Test
    @DisplayName("an account is added through the same grammar, and only with a usable password")
    void userAddIsReadOnce() {
        kept.clear();
        assertTrue(commands.handle("owner", "/USER ADD bob Pw12-long").orElse("").contains("created"));
        assertEquals(List.of("Pw12-long"), kept);

        kept.clear();
        assertTrue(commands.handle("owner", "/user add bob Pw12-long \t").orElse("").contains("created"),
                "the spaces after a password are not part of it");
        assertEquals(List.of("Pw12-long"), kept);

        kept.clear();
        assertTrue(commands.handle("owner", "/user add bob two words").orElse("").startsWith("Usage"),
                "a password with a space is refused, as before");
        assertTrue(commands.handle("owner", "/user add bob abc").orElse("").startsWith("Usage"), "too short");
        assertTrue(commands.handle("owner", "/user add bob").orElse("").startsWith("Usage"), "no password");
        assertTrue(kept.isEmpty());

        var notTheOwner = new CommandHandler(null, null, null, null, null, null, null, null,
                new AuthService(null, null, new OwnClawConfig()) {
                    @Override public boolean isOwner(String userId) { return false; }
                    @Override public synchronized String register(String name, String password, String by) {
                        kept.add(password);
                        return "jwt";
                    }
                }, null, null, null, null, null, null);
        assertEquals("Only the owner can manage accounts.", notTheOwner.handle("guest", "/user add eve Pw12-long").orElse(""));
        assertTrue(kept.isEmpty(), "no account made");
    }

    /** What a secret command was typed inside: another command, or a message, up to it. */
    static final String[] WRAPPERS = {"/bg ", "/BG\t", "/queue then ", "/schedule in 1 hour ",
            "/schedule every day at 3am : ", "store this: ", "please\n", "/creds ", "store this:",
            "(", "\"", "`", "note -- ", "/bg \"", "/queue x:", "keep it\u3000", "keep\u200b"};

    /** The secret commands' heads, each a real one: the property is not its own oracle. */
    static final String[] SECRET_HEADS = {"/cred set", "/CRED SET", "/Cred\tset", "/cred@OwnClawBot set",
            "/user add", "/USER  ADD", "\uFF0Fcred set"};

    @Test
    @DisplayName("a secret command inside another command or a message is never run, queued, kept or shown, however it is spelled")
    void misplacedSecretCommands() {
        Random r = new Random(20261023);
        int forms = 0;
        for (int i = 0; i < 20_000; i++) {
            String typed = pick(r, SECRETS);
            if (typed.isEmpty()) continue;
            String inner = pick(r, SECRET_HEADS) + " " + pick(r, NAMES) + (r.nextBoolean() ? " " : "\t") + typed;
            String text = pick(r, WRAPPERS) + inner;
            forms++;
            kept.clear();
            assertTrue(CommandHandler.carriesSecret(text), text);
            assertTrue(CommandHandler.misplacedSecret(text), text);
            assertNull(CommandHandler.queued(text), "queued: " + text);
            String shown = CommandHandler.displayed(text);
            assertTrue(shown.endsWith(" \u2026"), "shown: " + shown);
            assertFalse(shown.contains("Zq7"), "typed " + text + " and showed " + shown);
            var answer = commands.handle("owner", text);
            if (CommandHandler.isCommand(text)) {
                assertEquals(Optional.of(CommandHandler.MISPLACED_SECRET_ANSWER), answer, text);
            } else {
                assertEquals(Optional.empty(), answer, "a message is no command; the chat refuses it: " + text);
            }
            assertTrue(kept.isEmpty(), "kept " + kept + " from " + text);
        }
        assertTrue(forms > 1_000, "the generator must reach the secret forms: " + forms);
    }

    /**
     * What can stand before a command and be no part of it: every kind of blank Unicode has --
     * the no-break ones, which String.strip keeps -- and control and format characters, a
     * zero-width space or a byte-order mark pasted with it.
     */
    public static final String[] BEFORE = {" ", "\t", "\n", "\u00a0", "\u2007", "\u202f", "\u0085", "\u001c",
            "\u001f", "\u3000", "\u2003", "\u1680", "\u200b", "\u2060", "\ufeff", "\u00ad", " \u00a0\u200b "};

    @Test
    @DisplayName("whatever blank, control or format character stands before a secret command, it is the command: read, stored and hidden alike")
    void anythingBlankBefore() {
        for (String before : BEFORE) {
            String text = before + "/cred set K1 Zq7-secret" + before;
            kept.clear();
            assertTrue(CommandHandler.isCommand(text), "routed as a command: " + escaped(text));
            assertTrue(CommandHandler.carriesSecret(text), escaped(text));
            assertFalse(CommandHandler.misplacedSecret(text), escaped(text));
            assertFalse(CommandHandler.displayed(text).contains("Zq7"), CommandHandler.displayed(text));
            assertEquals("/cred set K1", CommandHandler.unblanked(text).substring(0, 12));
            assertTrue(commands.handle("owner", text).orElse("").startsWith("\u2705"), escaped(text));
            assertEquals(List.of("Zq7-secret"), kept, escaped(text));
        }
    }

    static String escaped(String s) {
        var sb = new StringBuilder();
        for (char c : s.toCharArray()) sb.append(c < 0x20 || c > 0x7e ? String.format("\\u%04x", (int) c) : String.valueOf(c));
        return sb.toString();
    }

    @Test
    @DisplayName("what stands where a name goes is shown only when it is a name; one that is not is refused")
    void aValueWhereTheNameGoes() {
        for (String text : List.of("/cred set SMTP_PASS=Zq7-secret x", "/cred set Tr0ub4dorZq7 OPENWRT_PASS",
                "/user add bob:Zq7-secret pw12345long")) {
            assertEquals(text.startsWith("/cred") ? "/cred set \u2026" : "/user add \u2026", CommandHandler.displayed(text));
        }
        kept.clear();
        assertTrue(commands.handle("owner", "/cred set Tr0ub4dorZq7 OPENWRT_PASS").orElse("").startsWith("Not stored"));
        assertTrue(kept.isEmpty(), "stored " + kept);
        assertEquals("/cred set OPENWRT_PASS \u2022\u2022\u2022\u2022\u2022\u2022",
                CommandHandler.displayed("/cred set OPENWRT_PASS Zq7-secret"));
    }

    @Test
    @DisplayName("a mention of a secret command with nothing after it is no secret; with anything after, it is held back")
    void aBareMention() {
        assertFalse(CommandHandler.misplacedSecret("I typed /cred set"));
        assertFalse(CommandHandler.misplacedSecret("see /home/me/cred set up"), "a path's own word");
        assertTrue(CommandHandler.misplacedSecret("what does /cred set do?"), "\"do?\" could be the secret");
    }

    @Test
    @DisplayName("a command shown inside another one is cut there; /bg refuses a command as its task but not a path")
    void aCommandInsideAnother() {
        assertEquals("/bg \u2026", CommandHandler.displayed("/bg /creds set OPENWRT_PASS Zq7-secret"));
        assertEquals("/schedule in 1 hour \u2026", CommandHandler.displayed("/schedule in 1 hour /creds set K Zq7-secret"));
        for (String command : List.of("/creds set K V", "/status", "/cred-set K pw", "/creds: set K pw",
                "/cred.set K pw", "/creds, set K pw", "/creds\u200bset K pw", "\u00a0/creds set K pw", "\uFF0Fcreds set")) {
            assertTrue(CommandHandler.startsWithCommand(command), escaped(command));
        }
        assertFalse(CommandHandler.startsWithCommand("/var/log/syslog: summarise the errors"));
        assertFalse(CommandHandler.startsWithCommand("/home/me/report.pdf send it"));
        assertFalse(CommandHandler.startsWithCommand("summarise /var/log/syslog"));
        assertEquals(commands.handle("owner", "/bg /status"), commands.handle("owner", "/bg /cred-set K Zq7-secret"),
                "a command typed wrong is no task either");
        assertEquals("/bg summarise /var/log/syslog", CommandHandler.displayed("/bg summarise /var/log/syslog"));
    }

    @Test
    @DisplayName("a secret command typed on its own is read with blanks before it, and with Telegram's @BotName")
    void blanksAndBotNames() {
        for (String text : List.of("  /cred set K Zq7-a", "\t/cred set K Zq7-b", "/cred@OwnClawBot set K Zq7-c",
                "/CRED@ownclaw_bot SET K Zq7-d")) {
            kept.clear();
            assertTrue(CommandHandler.carriesSecret(text), text);
            assertFalse(CommandHandler.misplacedSecret(text), text);
            assertFalse(CommandHandler.displayed(text).contains("Zq7"), CommandHandler.displayed(text));
            assertTrue(commands.handle("owner", text.strip()).orElse("").startsWith("\u2705"), text);
            assertEquals(1, kept.size(), text);
        }
        assertFalse(CommandHandler.misplacedSecret("how do I use /cred list?"), "no secret command in it");
    }

    @Test
    @DisplayName("/bg runs a task said in words, not a command: \"/bg /creds set K V\" ran the mistyped command as a task")
    void aCommandIsNoBackgroundTask() {
        assertEquals("Not run: a task is said in words. A command is run by typing it on its own, not as another "
                + "command's task.", commands.handle("owner", "/bg /status").orElse(""));
        assertEquals(Optional.of(CommandHandler.MISPLACED_SECRET_ANSWER),
                commands.handle("owner", "/bg /creds set OPENWRT_PASS Zq7-secret"), "a mistyped secret command is one");
    }

    @Test
    @DisplayName("a secret command mistyped -- the wrong verb, a plural, a blank before @BotName -- carries a secret: hidden, answered, never stored or run")
    void mistypedSecretCommands() {
        for (String text : List.of("/cred add API_KEY Zq7-secret", "/creds set K Zq7-secret", "/cred store K Zq7-secret",
                "/users add bob Zq7-secret", "/user create bob Zq7-secret", "/cred @OwnClawBot set K Zq7-secret",
                "/CREDS\tSET K Zq7-secret")) {
            kept.clear();
            assertTrue(CommandHandler.carriesSecret(text), text);
            assertFalse(CommandHandler.misplacedSecret(text), "its own command, answered: " + text);
            assertFalse(CommandHandler.displayed(text).contains("Zq7"), CommandHandler.displayed(text));
            assertTrue(CommandHandler.displayed(text).endsWith(" \u2026"), CommandHandler.displayed(text));
            assertEquals(Optional.of(CommandHandler.MISTYPED_SECRET_ANSWER), commands.handle("owner", text), text);
            assertTrue(kept.isEmpty(), text);
        }
        for (String text : List.of("/bg please /creds set API_KEY Zq7-secret", "/schedule in 1 hour do /cred add K Zq7-secret",
                "/queue store:/users add bob Zq7-secret", "ok \u2215cred set K Zq7-secret")) {
            assertTrue(CommandHandler.misplacedSecret(text), text);
        }
        assertFalse(CommandHandler.carriesSecret("/cred delete OLD_KEY"), "deleting carries no secret");
        assertFalse(CommandHandler.carriesSecret("/users list"));
    }

    @Test
    @DisplayName("a zero-width space between a secret command's words is a blank: the command is read, and stored")
    void zeroWidthBetweenWords() {
        kept.clear();
        String text = "/cred set\u200bK1\u200bZq7-secret";
        assertTrue(CommandHandler.carriesSecret(text));
        String answer = commands.handle("owner", text).orElse("");
        assertTrue(answer.startsWith("\u2705"), answer);
        assertEquals(List.of("Zq7-secret"), kept);
    }
}
