package com.ownclaw.users;

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

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final int IV_LENGTH = 12;       // bytes (96 bits, recommended for GCM)
    private static final int KEY_LENGTH = 256;     // bits
    private static final int PBKDF2_ITERATIONS = 100_000;

    private final JdbcTemplate jdbc;
    private final SecureRandom secureRandom = new SecureRandom();

    /** System-level master key, loaded on startup. */
    private String masterKey;

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
    }

    /**
     * Store or update a credential for a user.
     *
     * @param userId the user ID
     * @param key    credential key (e.g. SMTP_HOST, OPENAI_API_KEY)
     * @param value  plaintext credential value
     */
    public void storeCredential(String userId, String key, String value) {
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
    }

    /**
     * Retrieve and decrypt a credential value.
     *
     * @return decrypted plaintext value, or empty if not found
     */
    public Optional<String> getCredential(String userId, String key) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT encrypted_value, iv FROM credential_vault WHERE user_id = ? AND credential_key = ?",
                userId, key);

        if (rows.isEmpty()) return Optional.empty();

        try {
            String salt = getOrCreateUserSalt(userId);
            SecretKey secretKey = deriveKey(salt);

            String encB64 = (String) rows.getFirst().get("encrypted_value");
            String ivB64 = (String) rows.getFirst().get("iv");

            byte[] encrypted = Base64.getDecoder().decode(encB64);
            byte[] iv = Base64.getDecoder().decode(ivB64);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] decrypted = cipher.doFinal(encrypted);
            return Optional.of(new String(decrypted, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Failed to decrypt credential {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Retrieve and decrypt multiple credentials for skill execution.
     *
     * @param keys list of credential keys to fetch
     * @return Map of key → plaintext value (only keys that exist and decrypt successfully)
     */
    public Map<String, String> getCredentials(String userId, List<String> keys) {
        Map<String, String> result = new HashMap<>();
        for (String key : keys) {
            getCredential(userId, key).ifPresent(value -> result.put(key, value));
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
        jdbc.update("DELETE FROM credential_vault WHERE user_id = ? AND credential_key = ?", userId, key);
        log.info("Deleted credential: user={}, key={}", userId, key);
    }

    /**
     * Check if a credential exists for a user.
     */
    public boolean hasCredential(String userId, String key) {
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
