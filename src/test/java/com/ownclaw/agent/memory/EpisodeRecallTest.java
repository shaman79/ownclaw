package com.ownclaw.agent.memory;

import com.ownclaw.conversation.MigratedDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Past tasks, recalled when the agent asks: every match, whole, the best first. */
class EpisodeRecallTest {

    @Test
    @DisplayName("every episode sharing a word, whole: most words first, then newest; no count and no word limit")
    void everyMatch(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var memory = new SqliteAgentMemory(jdbc);
        String longResponse = "Response: " + "port 22 was open on the router. ".repeat(500);
        for (int i = 0; i < 5; i++) memory.storeEpisode("u1", "t" + i, "Task: lunch menu " + i, true, List.of());
        memory.storeEpisode("u1", "a0000001", "Task: router audit", false, List.of());
        memory.storeEpisode("u1", "c0000003", "Task: firewall", true, List.of("router_audit"));
        memory.storeEpisode("u1", "b0000002", "Task: router check\n" + longResponse, true, List.of());
        memory.storeEpisode("u2", "other001", "Task: router audit of someone else", true, List.of());
        jdbc.update("UPDATE agent_memory SET created_at = datetime('now', '-1 day') WHERE task_id = 'a0000001'");

        // Eleven words before the two that matter: the first ten used to be all that was looked for.
        var found = memory.recallEpisodes("u1",
                "please alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo audit router");
        var tasks = found.stream().map(AgentMemory.MemoryEntry::content).map(c -> c.lines().findFirst().orElseThrow()).toList();
        assertEquals(List.of("Task: firewall", "Task: router audit", "Task: router check"), tasks,
                "two words in common before one, however new; among equals the newer first");
        assertTrue(found.get(2).content().endsWith(longResponse), "whole");
        assertTrue(memory.recallEpisodes("u1", "ŠKODA").isEmpty());
        memory.storeEpisode("u1", "czech001", "Task: servis Škoda", true, List.of());
        assertEquals(1, memory.recallEpisodes("u1", "ŠKODA").size(), "any letter case, any script");
        assertTrue(memory.recallEpisodes("u1", "the and of").isEmpty(), "no word worth looking for, no match");
    }
}
