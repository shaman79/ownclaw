package com.ownclaw.users;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * User management — creates, finds, and updates user profiles.
 * Phase 1: single-user mode (auto-creates default user on first message).
 */
@Service
public class UserRepository {

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Find user by their Telegram ID.
     */
    public Optional<String> findByTelegramId(long telegramId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id FROM users WHERE telegram_id = ?", telegramId);
        return rows.isEmpty() ? Optional.empty() : Optional.of((String) rows.getFirst().get("id"));
    }

    /**
     * Find user by internal ID.
     */
    public Optional<Map<String, Object>> findById(String userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM users WHERE id = ?", userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /**
     * Create a new user profile.
     *
     * @return the new user ID
     */
    public String createUser(String displayName, Long telegramId) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
            INSERT INTO users (id, display_name, telegram_id) VALUES (?, ?, ?)
            """, id, displayName, telegramId);
        return id;
    }

    /**
     * Find a login account (one with a password) by username.
     */
    public Optional<String> findAccountByUsername(String username) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id FROM users WHERE display_name = ? AND password_hash IS NOT NULL", username);
        return rows.isEmpty() ? Optional.empty() : Optional.of((String) rows.getFirst().get("id"));
    }

    /**
     * All users, oldest first. Never includes password hashes or salts.
     */
    public List<Map<String, Object>> listUsers() {
        return jdbc.queryForList("""
            SELECT id, display_name, telegram_id, created_at,
                   password_hash IS NOT NULL AS has_password
            FROM users ORDER BY created_at, rowid
            """);
    }

    /**
     * Link a Telegram ID to a user so that messages from it are accepted; {@code null} unlinks.
     *
     * @throws org.springframework.dao.DataAccessException if the ID is already linked to someone
     */
    public void linkTelegram(String userId, Long telegramId) {
        jdbc.update("UPDATE users SET telegram_id = ?, updated_at = datetime('now') WHERE id = ?",
                telegramId, userId);
    }

    /**
     * Revoke all access: the user can no longer log in, existing tokens stop validating
     * (see AuthService.validateToken) and their Telegram ID is no longer recognised.
     * The row and the user's data stay, so nothing referencing it breaks.
     */
    public boolean disable(String userId) {
        return jdbc.update("""
            UPDATE users SET password_hash = NULL, telegram_id = NULL, updated_at = datetime('now')
            WHERE id = ?
            """, userId) > 0;
    }

    /**
     * Get or create a default user (Phase 1: single-user mode).
     */
    public String getDefaultUserId() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id FROM users LIMIT 1");
        if (!rows.isEmpty()) {
            return (String) rows.getFirst().get("id");
        }
        return createUser("default", null);
    }
}
