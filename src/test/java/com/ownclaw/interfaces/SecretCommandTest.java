package com.ownclaw.interfaces;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.users.AuthService;
import com.ownclaw.users.CredentialVault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One grammar: whatever {@link CommandHandler#handle} stores as a secret, {@link CommandHandler#displayed}
 * hides; a text that begins with a secret command shows none of the secret it was typed with, whether
 * or not it can be stored; and whatever the grammar does not read as a secret command is shown
 * exactly as sent. Checked as a property over spellings, spacing and case, because the gaps between
 * two readings of the same text are always in the spellings nobody listed.
 */
class SecretCommandTest {

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
            }, null, null, null, null);

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
                }, null, null, null, null);
        assertEquals("Only the owner can manage accounts.", notTheOwner.handle("guest", "/user add eve Pw12-long").orElse(""));
        assertTrue(kept.isEmpty(), "no account made");
    }
}
