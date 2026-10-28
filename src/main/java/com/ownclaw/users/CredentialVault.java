package com.ownclaw.users;

import com.ownclaw.privacy.Redactor;
import com.ownclaw.privacy.VaultSecrets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.*;

/**
 * Encrypted credential vault using AES-256-GCM.
 * Per-user encryption: derives a unique key from the system master key + per-user salt.
 * Credentials are stored encrypted in SQLite and decrypted on demand for skill execution.
 */
@Service
public class CredentialVault {

    private static final Logger log = LoggerFactory.getLogger(CredentialVault.class);

    /**
     * The secret a link that opens the chat with a message is signed with (LinkController): what
     * shows the owner "Guardian alert: verified". Never handed to a skill -- a skill the agent
     * wrote that could read it could sign any message as the guardian's.
     */
    public static final String LINK_SECRET = "GUARDIAN_LINK_SECRET";

    /** Whether a skill may be handed this credential: every one but {@link #LINK_SECRET}. */
    public static boolean forSkills(String key) {
        return key == null || !LINK_SECRET.equals(key.strip().toUpperCase(java.util.Locale.ROOT));
    }

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final int IV_LENGTH = 12;       // bytes (96 bits, recommended for GCM)
    private static final int KEY_LENGTH = 256;     // bits
    private static final int PBKDF2_ITERATIONS = 100_000;

    private final JdbcTemplate jdbc;
    private final SecureRandom secureRandom = new SecureRandom();

    /** System-level master key, loaded on startup. */
    private String masterKey;

    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]*");

    /**
     * A line of a value that is another secret command: two typed in one message were stored as
     * one value, the second command and its secret inside the first's.
     */
    private static final java.util.regex.Pattern ANOTHER_COMMAND = java.util.regex.Pattern.compile(
            "(?m)^[\\s\\p{Cf}]*[/\\uFF0F](?:cred|user)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * Why a name is refused, wherever {@link #validName} says no. It does not repeat the name: a
     * value typed where the name goes -- "/cred set SMTP_PASS=hunter2 x" -- would be shown back,
     * and the name it was stored under went to the cloud model with every prompt, as one of the
     * keys the vault holds.
     */
    public static final String NAME_RULE = "a credential's name is letters, digits and underscores, "
            + "in one case, starting with a letter: the skill reads it, upper-cased, from the variable "
            + "of that name.";

    /**
     * Whether {@code name} may name a credential: upper-case letters, digits and underscores,
     * starting with a letter -- the name of the variable a skill finds it in.
     */
    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /**
     * Why {@code key}, as typed, and {@code value} cannot be stored, or null when they can: the
     * one rule, for {@link #storeCredential} and for {@code /cred set}, which answers with it.
     * <p>
     * The name, upper-cased, is a {@link #validName}, and was typed in one case: a value typed
     * where the name goes -- "SMTP_PASS=hunter2", "Tr0ub4dor" before the name -- is refused when
     * it has what a name does not, and the names the vault holds go to the cloud model with every
     * prompt. A value that looks like a name ("hunter2") cannot be told from one.
     * A NUL cannot be in a variable's value, and the JDK's refusal to put one there quoted the
     * whole value, into the log and the task's result.
     */
    public static String refusal(String key, String value) {
        String typed = key == null ? "" : key.strip();
        boolean oneCase = typed.equals(typed.toUpperCase(Locale.ROOT)) || typed.equals(typed.toLowerCase(Locale.ROOT));
        if (!oneCase || !validName(typed.toUpperCase(Locale.ROOT))) return NAME_RULE;
        if (value != null && value.indexOf('\u0000') >= 0) return "a credential's value cannot hold a NUL character.";
        if (value != null && ANOTHER_COMMAND.matcher(value).find()) {
            return "one secret per message: a line of the value is another /cred set or /user add, "
                    + "and it would have been stored as part of this one.";
        }
        return null;
    }

    public CredentialVault(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Initialize the vault. Must be called after Spring context is ready.
     * Loads or generates the system master key from the system_settings table.
     */
    @jakarta.annotation.PostConstruct
    public void init() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT value FROM system_settings WHERE key = 'vault_master_key'");
        if (rows.isEmpty()) {
            // Generate a new random master key (hex-encoded 32 bytes)
            byte[] keyBytes = new byte[32];
            secureRandom.nextBytes(keyBytes);
            masterKey = Base64.getEncoder().encodeToString(keyBytes);
            jdbc.update("INSERT INTO system_settings (key, value) VALUES (?, ?)",
                    "vault_master_key", masterKey);
            log.info("Generated new vault master key");
        } else {
            masterKey = (String) rows.getFirst().get("value");
            log.info("Loaded vault master key from system_settings");
        }
        // What every text that leaves is scrubbed with, from the first: the log included.
        VaultSecrets.clear();
        try {
            for (String userId : jdbc.queryForList("SELECT DISTINCT user_id FROM credential_vault", String.class)) {
                publishSecrets(userId);
            }
        } catch (Exception e) {
            log.error("Could not read the vault's users for scrubbing: {}", e.getMessage());
        }
    }

    /**
     * Store or update a credential for a user.
     *
     * @param userId the user ID
     * @param key    credential key (e.g. SMTP_HOST, OPENAI_API_KEY)
     * @param value  plaintext credential value
     * @throws IllegalArgumentException when the key, upper-cased, is not a {@link #validName};
     *                                  nothing is stored
     */
    public void storeCredential(String userId, String key, String value) {
        String refused = refusal(key, value);
        if (refused != null) throw new IllegalArgumentException(refused);
        key = key.strip().toUpperCase(Locale.ROOT);
        value = value.strip();
        try {
            String salt = getOrCreateUserSalt(userId);
            SecretKey secretKey = deriveKey(salt);

            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));

            String encB64 = Base64.getEncoder().encodeToString(encrypted);
            String ivB64 = Base64.getEncoder().encodeToString(iv);

            jdbc.update("""
                INSERT INTO credential_vault (user_id, credential_key, encrypted_value, iv)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(user_id, credential_key) DO UPDATE SET
                    encrypted_value = excluded.encrypted_value,
                    iv = excluded.iv,
                    updated_at = datetime('now')
                """, userId, key, encB64, ivB64);

            log.info("Stored credential: user={}, key={}", userId, key);
        } catch (Exception e) {
            log.error("Failed to encrypt credential: {}", e.getMessage());
            throw new RuntimeException("Failed to store credential", e);
        }
        publishSecrets(userId);
    }

    /**
     * Retrieve and decrypt a credential value.
     *
     * @return decrypted plaintext value, or empty if not found
     */
    public Optional<String> getCredential(String userId, String key) {
        return Optional.ofNullable(getCredentials(userId, List.of(key)).get(key));
    }

    /**
     * Retrieve and decrypt multiple credentials for skill execution.
     * <p>
     * The user's key is derived once, for the first of them stored: PBKDF2 is what a read costs,
     * about a fifth of a second, and deriving it for each credential held a task's start, a skill's
     * run and every /cred set -- on the chat's own thread -- for that much per credential.
     *
     * @param keys list of credential keys to fetch
     * @return Map of key → plaintext value (only keys that exist and decrypt successfully)
     */
    public Map<String, String> getCredentials(String userId, List<String> keys) {
        Map<String, String> result = new HashMap<>();
        SecretKey secretKey = null;
        for (String key : keys) {
            String name = key.strip().toUpperCase(Locale.ROOT);
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT encrypted_value, iv FROM credential_vault WHERE user_id = ? AND credential_key = ?",
                    userId, name);
            if (rows.isEmpty()) continue;
            try {
                if (secretKey == null) secretKey = deriveKey(getOrCreateUserSalt(userId));

                String encB64 = (String) rows.getFirst().get("encrypted_value");
                String ivB64 = (String) rows.getFirst().get("iv");

                byte[] encrypted = Base64.getDecoder().decode(encB64);
                byte[] iv = Base64.getDecoder().decode(ivB64);

                Cipher cipher = Cipher.getInstance(ALGORITHM);
                cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

                byte[] decrypted = cipher.doFinal(encrypted);
                result.put(key, new String(decrypted, StandardCharsets.UTF_8));
            } catch (Exception e) {
                log.error("Failed to decrypt credential {}: {}", name, e.getMessage());
            }
        }
        return result;
    }

    /**
     * List all credential keys for a user (without decrypting values).
     */
    public List<String> listCredentialKeys(String userId) {
        return jdbc.queryForList(
                "SELECT credential_key FROM credential_vault WHERE user_id = ? ORDER BY credential_key",
                String.class, userId);
    }

    /**
     * Delete a credential.
     */
    public void deleteCredential(String userId, String key) {
        key = key.strip().toUpperCase(Locale.ROOT);
        jdbc.update("DELETE FROM credential_vault WHERE user_id = ? AND credential_key = ?", userId, key);
        log.info("Deleted credential: user={}, key={}", userId, key);
        publishSecrets(userId);
    }

    /**
     * Put what the vault holds for {@code userId} now -- the values of the keys
     * {@link Redactor#isSecretName} calls secrets -- where every text that leaves is scrubbed
     * with them ({@link VaultSecrets}). A failure is logged and leaves the last values there:
     * a scrub that removes a value no longer stored does no harm. One at a time: a /cred set on
     * the web and one on Telegram read the vault in turn, so the later read is the one kept.
     */
    private synchronized void publishSecrets(String userId) {
        try {
            List<String> secret = listCredentialKeys(userId).stream().filter(Redactor::isSecretName).toList();
            VaultSecrets.set(userId, secret.isEmpty() ? Map.of() : getCredentials(userId, secret));
        } catch (Exception e) {
            log.error("Could not read the secret credentials of user {} for scrubbing: {}", userId, e.getMessage());
        }
    }

    /**
     * Check if a credential exists for a user.
     */
    public boolean hasCredential(String userId, String key) {
        key = key.strip().toUpperCase(Locale.ROOT);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM credential_vault WHERE user_id = ? AND credential_key = ?",
                Integer.class, userId, key);
        return count != null && count > 0;
    }

    // --- Internal ---

    /**
     * Derive AES-256 key from master key + user salt using PBKDF2.
     */
    private SecretKey deriveKey(String userSalt) throws Exception {
        KeySpec spec = new PBEKeySpec(
                masterKey.toCharArray(),
                userSalt.getBytes(StandardCharsets.UTF_8),
                PBKDF2_ITERATIONS,
                KEY_LENGTH);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * Get or create encryption salt for a user.
     */
    private String getOrCreateUserSalt(String userId) {
        List<String> salts = jdbc.queryForList(
                "SELECT encryption_salt FROM users WHERE id = ?", String.class, userId);

        if (!salts.isEmpty() && salts.getFirst() != null) {
            return salts.getFirst();
        }

        // Generate and store a new salt
        byte[] salt = new byte[16];
        secureRandom.nextBytes(salt);
        String saltB64 = Base64.getEncoder().encodeToString(salt);
        jdbc.update("UPDATE users SET encryption_salt = ? WHERE id = ?", saltB64, userId);
        return saltB64;
    }
}
