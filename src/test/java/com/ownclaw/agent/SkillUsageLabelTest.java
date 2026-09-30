package com.ownclaw.agent;

import com.ownclaw.privacy.Label;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The side door into the code-generation prompt.
 * <p>
 * The repair path reads a skill's recent failures — parameters and error — and puts them in
 * front of the cloud. A private skill's traceback carries what it fetched. The row keeps the
 * error, because it is the owner's diagnostic; the query that feeds the cloud takes only
 * PUBLIC rows. First SQLite-backed test in the suite: the real migrations, the real service.
 */
class SkillUsageLabelTest {

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("src/main/java"))) p = p.getParent();
        if (p == null) throw new IllegalStateException(
                "src/main/java not found above " + Path.of("").toAbsolutePath());
        return p;
    }

    private static JdbcTemplate db(Path tmp) throws Exception {
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + tmp.resolve("t.db"));
        var jdbc = new JdbcTemplate(ds);
        for (String f : List.of("007-skill-usage.sql", "016-skill-usage-repro.sql", "017-skill-usage-label.sql")) {
            // Found by walking up, not by a relative guess: the suite is launched from more
            // than one working directory and a fixed "../../.." silently became NoSuchFile.
            Path sql = repoRoot().resolve("src/main/resources/db/changelog").resolve(f);
            String text = Files.readString(sql).lines()
                    .filter(l -> !l.strip().startsWith("--")).reduce("", (a, b) -> a + "\n" + b);
            Arrays.stream(text.split(";")).map(String::strip).filter(st -> !st.isEmpty())
                    .forEach(jdbc::execute);
        }
        return jdbc;
    }

    @Test
    @DisplayName("the repair evidence is PUBLIC rows only; the private error is still in the table")
    void repairEvidenceIsPublicOnly(@TempDir Path tmp) throws Exception {
        var jdbc = db(tmp);
        var curator = new SkillCuratorService(jdbc, null, null, null);

        curator.recordUsage("imap_fetch", "u1", "t1", false, 10,
                Map.of("folder", "INBOX"), "Traceback: mailbox petr@example.com", Label.PRIVATE);
        curator.recordUsage("imap_fetch", "u1", "t2", false, 10,
                Map.of("folder", "INBOX"), "Traceback: KeyError 'uid'", Label.PUBLIC);
        curator.recordUsage("imap_fetch", "u1", "t3", false, 10,
                Map.of("folder", "INBOX"), "old row, no label");   // pre-slice shape

        var evidence = curator.failures("imap_fetch");
        assertEquals(1, evidence.size(), "one PUBLIC row; the private one and the unlabelled one stay out");
        assertEquals("Traceback: KeyError 'uid'", evidence.get(0).get("error"));

        Integer all = jdbc.queryForObject("SELECT count(*) FROM skill_usage WHERE success = 0", Integer.class);
        assertEquals(3, all, "nothing is dropped from the table itself — it is the owner's diagnostic");
        String priv = jdbc.queryForObject(
                "SELECT error FROM skill_usage WHERE label = 'PRIVATE'", String.class);
        assertTrue(priv.contains("petr@example.com"), "stored in full, read through ops");
        // Mutation: drop the WHERE label clause -> three rows come back.
    }

    @Test
    @DisplayName("a failure is kept whole -- its error, its parameters -- with secret-named ones redacted; repeats are one row")
    void failuresAreKeptWhole(@TempDir Path tmp) throws Exception {
        var jdbc = db(tmp);
        var curator = new SkillCuratorService(jdbc, null, null, null);
        String error = "Traceback (most recent call last):\n" + "  File \"skill.py\", line 9\n".repeat(200) + "KeyError: 'uid'";
        String folder = "INBOX/" + "Archive/".repeat(600);
        for (String task : List.of("t1", "t2")) {
            curator.recordUsage("imap_fetch", "u1", task, false, 10,
                    Map.of("folder", folder, "api_key", "sk-live-value"), error, Label.PUBLIC);
        }
        curator.recordUsage("imap_fetch", "u1", "t3", false, 10, Map.of("folder", "INBOX"), "timed out", Label.PUBLIC);

        var failures = curator.failures("imap_fetch");
        assertEquals(2, failures.size(), "the same call failing the same way is one row");
        assertEquals("timed out", failures.get(0).get("error"), "newest first");
        assertEquals(error, failures.get(1).get("error"), "the error, whole");
        assertEquals(2, ((Number) failures.get(1).get("times")).intValue());
        var params = new com.fasterxml.jackson.databind.ObjectMapper().readTree(String.valueOf(failures.get(1).get("params_json")));
        assertEquals(folder, params.path("folder").asText(), "the parameters, whole and still JSON");
        assertEquals("[REDACTED]", params.path("api_key").asText());
    }
}
