package com.ownclaw.users;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which skill may read which credential of which user: the rows of {@code credential_grants}.
 * <p>
 * A skill is code the agent wrote, and it declares the credentials it wants. Declaring one is not
 * being allowed it: the vault hands a skill a value only when the user it belongs to granted that
 * skill that credential ({@link CredentialVault#forSkill}, the one place a skill's credentials are
 * read). A grant is made by its user alone, in a command they send ({@code /grant}) -- nothing a
 * model can call makes one -- and taken back the same way ({@code /revoke}).
 * <p>
 * What a grant covers is a skill's name and a credential's name. A skill rewritten under its name
 * keeps what it was granted, as it must for a repair to leave it working; a credential it declares
 * anew is not granted with the rest.
 * <p>
 * Until the release that added this check, {@code /grant} and {@code /revoke} wrote the table and
 * nothing read it: a skill was handed every vault value it declared.
 */
@Service
public class CredentialGrantService {

    private static final Logger log = LoggerFactory.getLogger(CredentialGrantService.class);

    /** The row changeset 028 leaves in system_settings: {@link #PENDING} until {@link #grantExisting} ran. */
    static final String EXISTING = "credential_grants.existing";
    static final String PENDING = "pending";

    /** The one kind of row there is. The column is from the first schema, which had three and read none. */
    private static final String GRANTED = "permanent";

    private final JdbcTemplate jdbc;

    public CredentialGrantService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A credential's name as the vault stores it. */
    static String key(String name) {
        return name == null ? "" : name.strip().toUpperCase(Locale.ROOT);
    }

    /**
     * Those of {@code keys} this user has not granted this skill, in their order, each once and as
     * the vault names it. A user of null has no vault, and nothing is handed over for one: none.
     */
    public List<String> ungranted(String userId, String skill, Collection<String> keys) {
        if (userId == null || keys == null || keys.isEmpty()) return List.of();
        Set<String> granted = new HashSet<>(granted(userId, skill));
        var out = new LinkedHashSet<String>();
        for (String name : keys) {
            String key = key(name);
            if (!key.isEmpty() && !granted.contains(key)) out.add(key);
        }
        return List.copyOf(out);
    }

    /** The credentials this user granted this skill, by name. */
    public List<String> granted(String userId, String skill) {
        return jdbc.queryForList(
                "SELECT credential FROM credential_grants WHERE user_id = ? AND skill_name = ? AND grant_type = ? "
                        + "ORDER BY credential", String.class, userId, skill, GRANTED);
    }

    /** Every grant of this user: skill by skill, each with its credentials, by name. */
    public Map<String, List<String>> all(String userId) {
        var out = new LinkedHashMap<String, List<String>>();
        jdbc.query("SELECT skill_name, credential FROM credential_grants WHERE user_id = ? AND grant_type = ? "
                        + "ORDER BY skill_name, credential",
                rs -> { out.computeIfAbsent(rs.getString(1), s -> new ArrayList<>()).add(rs.getString(2)); },
                userId, GRANTED);
        return out;
    }

    /** This user lets this skill read these credentials of theirs. */
    public void grant(String userId, String skill, Collection<String> keys) {
        for (String name : keys) {
            jdbc.update("""
                    INSERT INTO credential_grants (user_id, skill_name, credential, grant_type)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(user_id, skill_name, credential) DO UPDATE SET
                        grant_type = excluded.grant_type,
                        granted_at = datetime('now')
                    """, userId, skill, key(name), GRANTED);
        }
        log.info("Credential grant: user={}, skill={}, credentials={}", userId, skill, keys);
    }

    /**
     * This user takes back what they granted this skill: these credentials, or all of them when
     * none is named. Returns the ones that had been granted, by name.
     */
    public List<String> revoke(String userId, String skill, Collection<String> keys) {
        var asked = new LinkedHashSet<String>();
        if (keys != null) for (String name : keys) asked.add(key(name));
        var revoked = new ArrayList<String>();
        for (String granted : granted(userId, skill)) {
            if (!asked.isEmpty() && !asked.contains(granted)) continue;
            jdbc.update("DELETE FROM credential_grants WHERE user_id = ? AND skill_name = ? AND credential = ?",
                    userId, skill, granted);
            revoked.add(granted);
        }
        // What is left of a row that was never a grant (the first schema's 'declined', 'one-time').
        if (asked.isEmpty()) {
            jdbc.update("DELETE FROM credential_grants WHERE user_id = ? AND skill_name = ?", userId, skill);
        }
        log.info("Credential grants revoked: user={}, skill={}, credentials={}", userId, skill, revoked);
        return revoked;
    }

    /**
     * Once, at the first start of the release that checks grants: every skill there is keeps the
     * credentials it was being handed. Each credential a skill declares is granted to it for every
     * user whose vault holds one of that name -- the pairs that worked until now, and no others: a
     * credential nobody stored is granted to nobody. Asked for by changeset 028's row, and that row
     * says when it was done, so a skill made afterwards is never granted here, whatever restarts.
     * <p>
     * Granting twice changes nothing, and the row is written last: a start that died half way
     * finishes it at the next. A failure is not caught -- a service that started without these
     * grants would refuse every skill that holds a credential.
     *
     * @param declared each skill's name with the credentials it declares
     * @return how many grants were made
     */
    public int grantExisting(Map<String, ? extends Collection<String>> declared) {
        List<String> asked = jdbc.queryForList("SELECT value FROM system_settings WHERE key = ?", String.class, EXISTING);
        if (asked.isEmpty() || !PENDING.equals(asked.getFirst())) return 0;
        int made = 0;
        for (var skill : declared.entrySet()) {
            var keys = new LinkedHashSet<String>();
            for (String name : skill.getValue()) {
                if (CredentialVault.forSkills(name) && !key(name).isEmpty()) keys.add(key(name));
            }
            for (String key : keys) {
                made += jdbc.update("""
                        INSERT OR IGNORE INTO credential_grants (user_id, skill_name, credential, grant_type)
                        SELECT user_id, ?, ?, ? FROM credential_vault WHERE credential_key = ?
                        """, skill.getKey(), key, GRANTED, key);
            }
        }
        jdbc.update("UPDATE system_settings SET value = 'done ' || datetime('now'), updated_at = datetime('now') "
                + "WHERE key = ?", EXISTING);
        log.info("Credential grants: {} skill(s) kept the credentials they were being handed -- {} grant(s) made, "
                + "once. From here a skill reads a credential only when its user granted it (/grant).",
                declared.size(), made);
        return made;
    }

    /** The command that grants this skill these credentials: what its user sends, or clicks. */
    public static String command(String skill, Collection<String> keys) {
        return "/grant " + skill + " " + String.join(" ", keys);
    }

    /**
     * Why a skill was not run, for whoever called it: its user has not granted it these
     * credentials, and only they can.
     */
    public static String notAllowed(String skill, List<String> keys) {
        return "'" + skill + "' has not been allowed to read " + String.join(", ", keys) + " from the vault. "
                + "Only the user can allow it, by sending `" + command(skill, keys) + "` in the chat: the task's "
                + "answer shows them that command. Nothing you can call allows it. Go on with what does not need "
                + "this skill, and say that it waits for them.";
    }

    /** The line a task's answer gets for a skill that was not run, for its user. */
    public static String asked(String skill, List<String> keys) {
        return "_🔑 " + skill + " was not run: you have not allowed it to read " + String.join(", ", keys)
                + " from your vault. To allow it, send:_ `" + command(skill, keys) + "`";
    }
}
