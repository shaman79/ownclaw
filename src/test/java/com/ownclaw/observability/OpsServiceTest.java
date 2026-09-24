package com.ownclaw.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.conversation.MigratedDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
        var ops = new OpsService(new OwnClawConfig(), jdbc, null, null, null, null, null, new ObjectMapper());

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
}
