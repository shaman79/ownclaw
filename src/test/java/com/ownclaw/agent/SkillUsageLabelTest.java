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

    private static JdbcTemplate db(Path tmp) throws Exception {
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + tmp.resolve("t.db"));
        var jdbc = new JdbcTemplate(ds);
        for (String f : List.of("007-skill-usage.sql", "016-skill-usage-repro.sql", "017-skill-usage-label.sql")) {
            Path sql = Path.of("src/main/resources/db/changelog", f);
            if (!Files.exists(sql)) sql = Path.of("../../..").resolve(sql);
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

        var evidence = curator.recentFailures("imap_fetch", 5);
        assertEquals(1, evidence.size(), "one PUBLIC row; the private one and the unlabelled one stay out");
        assertEquals("Traceback: KeyError 'uid'", evidence.get(0).get("error"));

        Integer all = jdbc.queryForObject("SELECT count(*) FROM skill_usage WHERE success = 0", Integer.class);
        assertEquals(3, all, "nothing is dropped from the table itself — it is the owner's diagnostic");
        String priv = jdbc.queryForObject(
                "SELECT error FROM skill_usage WHERE label = 'PRIVATE'", String.class);
        assertTrue(priv.contains("petr@example.com"), "stored in full, read through ops");
        // Mutation: drop the WHERE label clause -> three rows come back.
    }
}
