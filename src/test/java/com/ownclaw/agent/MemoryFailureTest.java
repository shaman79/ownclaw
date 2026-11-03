package com.ownclaw.agent;

import com.ownclaw.agent.memory.SqliteAgentMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.ownclaw.agent.LoopRig.call;
import static com.ownclaw.agent.LoopRig.respond;
import static com.ownclaw.agent.TaskEndToEndTest.sentTo;
import static com.ownclaw.agent.TaskEndToEndTest.session;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A memory that cannot be written or read says so, through the real loop and the real store.
 * Every failure used to be caught and logged in the store: a fact never stored was "Remembered",
 * a store that could not be read had "No facts stored", a delete that failed was "not found", and
 * a task ran without the owner's preferences with nothing to tell it so.
 */
class MemoryFailureTest {

    private static List<String> facts(LoopRig rig) {
        return rig.jdbc.queryForList("SELECT content FROM agent_memory WHERE memory_type = 'fact'", String.class);
    }

    @Test
    @DisplayName("a fact that could not be stored is an error, not 'Remembered' -- and the one stored under its key before is kept")
    void aFailedStoreIsSaid(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        new SqliteAgentMemory(rig.jdbc).storeFact("u1", "email_style", "OLD-MARKER casual");
        rig.jdbc.execute("CREATE TRIGGER disk_full BEFORE INSERT ON agent_memory WHEN NEW.memory_type = 'fact' "
                + "BEGIN SELECT RAISE(ABORT, 'database or disk is full'); END");
        rig.cloud.think.add(call(AgentAction.MEMORY_MANAGE,
                Map.of("action", "store", "key", "email_style", "content", "FACT-MARKER always formal")));
        rig.cloud.think.add(respond("noted"));
        rig.turn(session(rig), "remember that my emails are always formal");

        String told = sentTo(rig, 1);
        assertTrue(told.contains("ERROR: Failed to store fact:") && told.contains("database or disk is full"), told);
        assertFalse(told.contains("Remembered"), told);
        assertEquals(List.of("OLD-MARKER casual"), facts(rig),
                "the delete before the insert that failed took the old fact with it");
        // Mutations: catch and log in storeFact -> "Remembered"; two statements with no
        // transaction -> no fact left at all.
    }

    @Test
    @DisplayName("facts that cannot be read are said to be unreadable -- to the task at its start, and as an error to a list")
    void unreadableFactsAreSaid(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        rig.jdbc.execute("ALTER TABLE agent_memory RENAME TO agent_memory_elsewhere");
        rig.cloud.think.add(call(AgentAction.MEMORY_MANAGE, Map.of("action", "list")));
        rig.cloud.think.add(respond("noted"));
        rig.turn(session(rig), "what do you remember about me?");

        String first = sentTo(rig, 0);
        assertTrue(first.contains("## Preferences\n(The facts the user asked you to keep could not be read ("), first);
        String second = sentTo(rig, 1);
        assertTrue(second.contains("ERROR: could not read the stored facts:"), second);
        assertFalse(second.contains("No facts stored"), second);
        // Mutation: getFacts returns an empty list on failure -> no word of it at the start, and
        // "No facts stored" to the list.
    }

    @Test
    @DisplayName("a fact that could not be deleted is an error, not 'not found'")
    void aFailedDeleteIsSaid(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        new SqliteAgentMemory(rig.jdbc).storeFact("u1", "email_style", "casual");
        rig.jdbc.execute("CREATE TRIGGER io_error BEFORE DELETE ON agent_memory "
                + "BEGIN SELECT RAISE(ABORT, 'disk I/O error'); END");
        rig.cloud.think.add(call(AgentAction.MEMORY_MANAGE, Map.of("action", "delete", "key", "email_style")));
        rig.cloud.think.add(respond("noted"));
        rig.turn(session(rig), "forget my email style");

        String told = sentTo(rig, 1);
        assertTrue(told.contains("ERROR: Failed to delete fact:") && told.contains("disk I/O error"), told);
        assertFalse(told.contains("not found"), told);
        assertEquals(List.of("casual"), facts(rig));
    }
}
