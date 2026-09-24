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
    @DisplayName("private_content is redacted from SELECT * and refused when named or aliased")
    void privateContentNeverLeavesThroughOps(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var conversations = new ConversationService(jdbc, null);
        String session = conversations.createSession("u1", "Statements");
        conversations.saveMessage("u1", session, "assistant", "[Private answer]", List.of(), "a1b2c3d4", SECRET);
        var ops = new OpsService(new OwnClawConfig(), jdbc, null, null, null, null, null, new ObjectMapper());

        Map<String, Object> all = ops.query("SELECT * FROM conversations", null);
        assertNull(all.get("error"), String.valueOf(all));
        assertFalse(String.valueOf(all).contains("48,213.07"), String.valueOf(all));
        assertEquals(true, all.get("redactedColumns"));
        assertTrue(String.valueOf(all).contains("[Private answer]"), "the safe text stays readable");

        for (String sql : List.of("SELECT private_content FROM conversations",
                "SELECT COALESCE(private_content, content) AS c FROM conversations",
                "SELECT substr(PRIVATE_CONTENT, 1, 40) AS x FROM conversations")) {
            Map<String, Object> refused = ops.query(sql, null);
            assertTrue(String.valueOf(refused.get("error")).contains("forbidden identifier"), sql + " -> " + refused);
        }
    }
}
