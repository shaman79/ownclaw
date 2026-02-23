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
