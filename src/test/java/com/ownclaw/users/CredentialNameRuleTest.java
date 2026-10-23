package com.ownclaw.users;

import com.ownclaw.interfaces.CommandHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one rule for a credential's name -- the name of the variable a skill reads it from -- held
 * where a name is stored. A value typed where the name goes was stored as the name, and the names
 * the vault holds go to the cloud model with every prompt.
 */
class CredentialNameRuleTest {

    @Test
    @DisplayName("a name is upper-case letters, digits and underscores, starting with a letter")
    void theRule() {
        for (String name : List.of("SMTP_PASSWORD", "SSH_PRIVATE_KEY", "IMAP_USER", "X1", "A_")) {
            assertTrue(CredentialVault.validName(name), name);
        }
        for (String name : List.of("", "smtp_password", "1KEY", "API-KEY", "A B", "_X", "A.B", "A=B",
                "SMTP_PASS=HUNTER2", "K\u0000", "Ä")) {
            assertFalse(CredentialVault.validName(name), name);
        }
        assertFalse(CredentialVault.validName(null));
    }

    @Test
    @DisplayName("the vault refuses a bad name before it reaches for the database, and lower case is upper-cased first")
    void theVaultRefuses() {
        var vault = new CredentialVault(null);
        var e = assertThrows(IllegalArgumentException.class, () -> vault.storeCredential("u1", " api-key ", "x"));
        assertEquals(CredentialVault.NAME_RULE, e.getMessage());
        // A good name, lower-cased, passes the rule and goes on to the database -- here none.
        var past = assertThrows(RuntimeException.class, () -> vault.storeCredential("u1", "smtp_pass", "x"));
        assertEquals("Failed to store credential", past.getMessage());
    }

    @Test
    @DisplayName("/cred set refuses a bad name, stores nothing, and does not repeat what was typed")
    void credSetRefuses() {
        List<String> stored = new ArrayList<>();
        var vault = new CredentialVault(null) {
            @Override public void storeCredential(String userId, String key, String value) {
                stored.add(key);
            }
        };
        var commands = new CommandHandler(null, null, null, null, vault, null, null, null, null,
                null, null, null, null, null, null);

        assertEquals(Optional.of("Not stored: " + CredentialVault.NAME_RULE),
                commands.handle("u1", "/cred set api-key x"));
        Optional<String> typo = commands.handle("u1", "/cred set SMTP_PASS=hunter2 x");
        assertEquals(Optional.of("Not stored: " + CredentialVault.NAME_RULE), typo);
        assertFalse(typo.get().toLowerCase(Locale.ROOT).contains("hunter2"));
        assertTrue(stored.isEmpty(), "stored " + stored);

        assertTrue(commands.handle("u1", "/cred set ssh_private_key line one\nline two").get().contains("stored"));
        assertEquals(List.of("SSH_PRIVATE_KEY"), stored);
    }
}
