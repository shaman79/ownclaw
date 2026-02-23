package com.ownclaw.users;

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

    private final JdbcTemplate jdbc;
    private final UserRepository userRepo;
    private SecretKey jwtKey;

    public AuthService(JdbcTemplate jdbc, UserRepository userRepo) {
        this.jdbc = jdbc;
        this.userRepo = userRepo;
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
     * Register a new user with username and password.
     *
     * @return JWT token on success
     * @throws IllegalArgumentException if username already taken
     */
    public String register(String username, String password) {
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
            // Verify user still exists
            if (userRepo.findById(userId).isPresent()) {
                return Optional.of(userId);
            }
            return Optional.empty();
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
