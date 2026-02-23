package com.ownclaw.users;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Manages credential grant approvals per user per skill.
 * Grant types: permanent, one-time, declined.
 * Uses the credential_grants SQLite table.
 */
@Service
public class CredentialGrantService {

    private static final Logger log = LoggerFactory.getLogger(CredentialGrantService.class);

    private final JdbcTemplate jdbc;

    public CredentialGrantService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Check if a user has granted a skill access to a credential. */
    public GrantStatus checkGrant(String userId, String skillName, String credential) {
        List<String> grants = jdbc.queryForList(
                "SELECT grant_type FROM credential_grants WHERE user_id = ? AND skill_name = ? AND credential = ?",
                String.class, userId, skillName, credential);

        if (grants.isEmpty()) return GrantStatus.NONE;

        String type = grants.getFirst();
        return switch (type) {
            case "permanent" -> GrantStatus.PERMANENT;
            case "one-time" -> GrantStatus.ONE_TIME;
            case "declined" -> GrantStatus.DECLINED;
            default -> GrantStatus.NONE;
        };
    }

    /** Check if all required credentials for a skill are granted (permanent or one-time). */
    public boolean allGranted(String userId, String skillName, List<String> credentials) {
        if (credentials == null || credentials.isEmpty()) return true;
        return credentials.stream().allMatch(cred -> {
            GrantStatus status = checkGrant(userId, skillName, cred);
            return status == GrantStatus.PERMANENT || status == GrantStatus.ONE_TIME;
        });
    }

    /** Store a grant (upsert). */
    public void storeGrant(String userId, String skillName, String credential, String grantType) {
        jdbc.update("""
                INSERT INTO credential_grants (user_id, skill_name, credential, grant_type)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(user_id, skill_name, credential) DO UPDATE SET
                    grant_type = excluded.grant_type,
                    granted_at = datetime('now')
                """, userId, skillName, credential, grantType);
        log.info("Credential grant: user={}, skill={}, cred={}, type={}", userId, skillName, credential, grantType);
    }

    /** Store permanent grant for all credentials of a skill. */
    public void grantPermanent(String userId, String skillName, List<String> credentials) {
        credentials.forEach(cred -> storeGrant(userId, skillName, cred, "permanent"));
    }

    /** Store declined for all credentials of a skill. */
    public void decline(String userId, String skillName, List<String> credentials) {
        credentials.forEach(cred -> storeGrant(userId, skillName, cred, "declined"));
    }

    /** Remove all grants for a user+skill (reset). */
    public void resetGrants(String userId, String skillName) {
        jdbc.update("DELETE FROM credential_grants WHERE user_id = ? AND skill_name = ?", userId, skillName);
    }

    /** Remove all one-time grants (called after task execution). */
    public void clearOneTimeGrants(String userId) {
        jdbc.update("DELETE FROM credential_grants WHERE user_id = ? AND grant_type = 'one-time'", userId);
    }

    public enum GrantStatus {
        NONE, PERMANENT, ONE_TIME, DECLINED
    }
}
