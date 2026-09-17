package com.ownclaw.users;

import com.ownclaw.config.OwnClawConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Authentication service: register, login, JWT token management.
 * Uses BCrypt for password hashing and JJWT for token generation/validation.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** Thrown when anyone but the owner tries to create an account after the first one exists. */
    public static class RegistrationClosedException extends RuntimeException {
        public RegistrationClosedException() {
            super("Registration is closed. Ask the owner for an account.");
        }
    }

    private final JdbcTemplate jdbc;
    private final UserRepository userRepo;
    private final String configuredOwner;
    private SecretKey jwtKey;

    public AuthService(JdbcTemplate jdbc, UserRepository userRepo, OwnClawConfig config) {
        this.jdbc = jdbc;
        this.userRepo = userRepo;
        this.configuredOwner = config.getAuth().getOwner();
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        // Load or generate JWT signing key from system_settings
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT value FROM system_settings WHERE key = 'jwt_secret'");
        String secret;
        if (rows.isEmpty()) {
            byte[] keyBytes = new byte[64];
            new java.security.SecureRandom().nextBytes(keyBytes);
            secret = Base64.getEncoder().encodeToString(keyBytes);
            jdbc.update("INSERT INTO system_settings (key, value) VALUES (?, ?)",
                    "jwt_secret", secret);
            log.info("Generated new JWT signing key");
        } else {
            secret = (String) rows.getFirst().get("value");
        }
        // Ensure at least 256 bits for HMAC-SHA256
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            keyBytes = Arrays.copyOf(keyBytes, 64);
        }
        this.jwtKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Create an account with username and password.
     * <p>
     * Anyone may create the very first account (first-run bootstrap); that account is the
     * owner. After that only the owner can create accounts — the instance is reachable
     * from the internet, and every account can run code on the host.
     *
     * @param requesterId user ID of the authenticated caller, or {@code null} if anonymous
     * @return JWT token for the new account
     * @throws RegistrationClosedException if accounts exist and the caller is not the owner
     * @throws IllegalArgumentException if username already taken
     */
    public synchronized String register(String username, String password, String requesterId) {
        if (hasRegisteredUsers() && !isOwner(requesterId)) {
            log.warn("Rejected account creation for '{}' (requester={}): registration is closed",
                    username, requesterId != null ? requesterId : "anonymous");
            throw new RegistrationClosedException();
        }

        // Check if username already exists
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id FROM users WHERE display_name = ? AND password_hash IS NOT NULL",
                username);
        if (!existing.isEmpty()) {
            throw new IllegalArgumentException("Username already taken: " + username);
        }

        String passwordHash = BCrypt.hashpw(password, BCrypt.gensalt(12));
        String userId = UUID.randomUUID().toString().substring(0, 8);

        jdbc.update("""
            INSERT INTO users (id, display_name, password_hash) VALUES (?, ?, ?)
            """, userId, username, passwordHash);

        log.info("Registered user: {} ({})", username, userId);
        return generateToken(userId, username);
    }

    /**
     * Login with username and password.
     *
     * @return JWT token on success
     * @throws IllegalArgumentException if credentials invalid
     */
    public String login(String username, String password) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, password_hash FROM users WHERE display_name = ? AND password_hash IS NOT NULL",
                username);

        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Invalid username or password");
        }

        Map<String, Object> user = rows.getFirst();
        String passwordHash = (String) user.get("password_hash");
        String userId = (String) user.get("id");

        if (!BCrypt.checkpw(password, passwordHash)) {
            throw new IllegalArgumentException("Invalid username or password");
        }

        log.info("User logged in: {} ({})", username, userId);
        return generateToken(userId, username);
    }

    /**
     * Check whether any registered users (with password) exist.
     * Used by the login screen to decide between register vs. login mode.
     */
    public boolean hasRegisteredUsers() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE password_hash IS NOT NULL", Integer.class);
        return count != null && count > 0;
    }

    /**
     * The owner's user ID. There is no role column: the owner is the oldest login account,
     * i.e. whoever set the instance up. {@code ownclaw.auth.owner} (a username) overrides
     * this, as a recovery path if that ever picks the wrong account.
     */
    public Optional<String> ownerId() {
        if (configuredOwner != null && !configuredOwner.isBlank()) {
            Optional<String> configured = userRepo.findAccountByUsername(configuredOwner.trim());
            if (configured.isPresent()) {
                return configured;
            }
            log.warn("ownclaw.auth.owner='{}' matches no account — falling back to the oldest account",
                    configuredOwner);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id FROM users WHERE password_hash IS NOT NULL ORDER BY created_at, rowid LIMIT 1");
        return rows.isEmpty() ? Optional.empty() : Optional.of((String) rows.getFirst().get("id"));
    }

    public boolean isOwner(String userId) {
        return userId != null && ownerId().map(userId::equals).orElse(false);
    }

    /**
     * Validate a JWT token and return the user ID.
     *
     * @return userId if token is valid, empty otherwise
     */
    public Optional<String> validateToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(jwtKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String userId = claims.getSubject();
            // Verify the account still exists and has not been disabled. Tokens are only
            // ever issued to login accounts, and disabling one clears its password hash —
            // so this also cuts off tokens handed out before the account was disabled.
            boolean active = userRepo.findById(userId)
                    .map(user -> user.get("password_hash") != null)
                    .orElse(false);
            return active ? Optional.of(userId) : Optional.empty();
        } catch (Exception e) {
            log.debug("Invalid JWT token: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String generateToken(String userId, String username) {
        return Jwts.builder()
                .subject(userId)
                .claim("username", username)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000)) // 30 days
                .signWith(jwtKey)
                .compact();
    }
}
