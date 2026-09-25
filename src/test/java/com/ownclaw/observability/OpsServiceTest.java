package com.ownclaw.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.tools.DynamicSkillRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.sandbox.JailTestKit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ops API is read by sessions whose model runs in the cloud, so the owner's private answers
 * are redacted from its SQL results and may not be named in its SQL at all.
 */
class OpsServiceTest {

    static final String SECRET = "Closing balance 48,213.07 CZK";

    @Test
    @DisplayName("the conversations table is refused whole: no renaming reaches the private text")
    void privateContentNeverLeavesThroughOps(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var conversations = new ConversationService(jdbc, null);
        String session = conversations.createSession("u1", "Statements");
        conversations.saveMessage("u1", session, "assistant", "[Private answer]", List.of(), "a1b2c3d4", SECRET);
        var ops = new OpsService(new OwnClawConfig(), jdbc, null, null, null, null, null, new ObjectMapper(),
                JailTestKit.off());

        // Guarding the column was not enough: a CTE column list renames it without naming it.
        for (String sql : List.of("SELECT * FROM conversations",
                "SELECT private_content FROM conversations",
                "WITH c(a,b,c,d,e,f,g,h,i,j,k) AS (SELECT * FROM conversations) SELECT k FROM c",
                "SELECT * FROM main.\"Conversations\"",
                "SELECT x FROM (SELECT 1 AS x UNION SELECT * FROM [conversations])",
                "SELECT original_name FROM file_attachments")) {
            Map<String, Object> refused = ops.query(sql, null);
            assertTrue(String.valueOf(refused.get("error")).contains("forbidden identifier"), sql + " -> " + refused);
        }
        // The search index holds only the safe text, and stays readable.
        Map<String, Object> fts = ops.query("SELECT content FROM conversations_fts", null);
        assertNull(fts.get("error"), String.valueOf(fts));
        assertFalse(String.valueOf(fts).contains("48,213.07"), String.valueOf(fts));
    }

    @Test
    @DisplayName("the skills listing measures and hashes skill.py itself, never what a link there leads to")
    @SuppressWarnings("unchecked")
    void theSkillsListingFollowsNoLink(@TempDir Path tmp) throws Exception {
        Path env = Files.writeString(tmp.resolve(".env"), "OWNCLAW_OPS_TOKEN=canary-env-secret\n");
        Path generated = Files.createDirectories(tmp.resolve("skills/generated"));
        String code = "def run(params):\n    return {}\n";
        Files.writeString(Files.createDirectories(generated.resolve("plain")).resolve("skill.py"), code);
        Files.createSymbolicLink(Files.createDirectories(generated.resolve("linked_code")).resolve("skill.py"), env);
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
        Files.writeString(elsewhere.resolve("skill.py"), "# canary-elsewhere\n");
        Files.createSymbolicLink(generated.resolve("linked_dir"), elsewhere);
        var config = new OwnClawConfig();
        config.getSkills().setGeneratedPath(generated.toString());
        var ops = new OpsService(config, MigratedDatabase.at(tmp.resolve("t.db")), null,
                new DynamicSkillRegistry(config, null, null, null, null, null, null), null, null, null,
                new ObjectMapper(), JailTestKit.off());

        var rows = new java.util.HashMap<String, Map<String, Object>>();
        for (var row : (List<Map<String, Object>>) ops.skills(null).get("skills")) {
            rows.put((String) row.get("name"), row);
        }

        var plain = rows.get("plain");
        assertEquals((long) code.length(), plain.get("codeSizeBytes"));
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(code.getBytes(StandardCharsets.UTF_8))), plain.get("codeSha256"));
        var linked = rows.get("linked_code");
        assertEquals(-1L, linked.get("codeSizeBytes"), "the link's target was measured");
        assertEquals("", linked.get("codeModifiedAt"));
        assertEquals("", linked.get("codeSha256"), "the link's target was hashed");
        assertTrue(String.valueOf(linked.get("codeNote")).contains("not a regular file"), linked.toString());
        var dir = rows.get("linked_dir");
        assertEquals("a link, not followed", dir.get("note"));
        assertFalse(dir.containsKey("codeSha256"), "skill.py was hashed through a linked directory");
    }
}
