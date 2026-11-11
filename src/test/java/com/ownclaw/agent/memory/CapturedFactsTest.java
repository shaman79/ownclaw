package com.ownclaw.agent.memory;

import com.ownclaw.conversation.MigratedDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The record of the facts found in a chat turn: what is kept, what is on offer, what an undo puts
 * back -- each change whole, whatever another connection commits meanwhile.
 */
@Timeout(60)
class CapturedFactsTest {

    @TempDir
    Path tmp;

    /** The schema, on a database opened as production opens it: write-ahead log, busy timeout. */
    static JdbcTemplate asInProduction(Path file) throws Exception {
        MigratedDatabase.at(file);
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + file
                + "?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL"));
    }

    static final CapturedFacts.Row ROW = c -> "2026-11-11 12:00:00";

    static Map<String, String> facts(AgentMemory memory) {
        var out = new HashMap<String, String>();
        for (var e : memory.getFacts("u1")) out.put(CapturedFacts.keyOf(e), e.content());
        return out;
    }

    @Test
    @DisplayName("a commit another connection makes meanwhile loses nothing: the change holds the write lock from its first statement")
    void aCommitMeanwhileLosesNothing() throws Exception {
        var jdbc = asInProduction(tmp.resolve("t.db"));
        var memory = new SqliteAgentMemory(jdbc);
        var captured = new CapturedFacts(jdbc, memory, null);
        var meanwhile = new CompletableFuture<Void>();
        captured.afterFirstWrite = () -> {
            if (meanwhile.isDone() || Thread.currentThread().getName().equals("meanwhile")) return;
            var other = new Thread(() -> {
                try {
                    new SqliteAgentMemory(jdbc).storeFact("u1", "printer", "The printer is in the study.");
                    meanwhile.complete(null);
                } catch (RuntimeException e) {
                    meanwhile.completeExceptionally(e);
                }
            }, "meanwhile");
            other.start();
            try {
                meanwhile.get(1, TimeUnit.SECONDS);   // it waits for this change to commit
            } catch (TimeoutException waitsAsItShould) {
                // as it should
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };

        var saved = captured.write("u1", "s1", null, CapturedFacts.Source.OWNER, "nas", "The NAS is at 192.0.2.20.",
                false, ROW);

        assertNotNull(saved);
        meanwhile.get(10, TimeUnit.SECONDS);
        assertEquals(Map.of("nas", "The NAS is at 192.0.2.20.", "printer", "The printer is in the study."), facts(memory));
        assertNotNull(captured.find("u1", saved.capture().ref()).memoryId());
        // Mutation: read the facts before the capture's first write -> the commit meanwhile lands
        // between, and SQLite refuses this change's write (SQLITE_BUSY_SNAPSHOT).
    }

    @Test
    @DisplayName("an undo compares the fact's row, not its words: changed and changed back since, it is not undone")
    void undoComparesTheRow() throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var memory = new SqliteAgentMemory(jdbc);
        var captured = new CapturedFacts(jdbc, memory, null);
        var saved = captured.write("u1", "s1", null, CapturedFacts.Source.OWNER, "phone", "The owner's phone is a Pixel 9.",
                false, ROW);
        memory.storeFact("u1", "phone", "The owner's phone is a Pixel 10.");   // memory_manage changes it
        memory.storeFact("u1", "phone", "The owner's phone is a Pixel 9.");    // and back, to the same words

        var undone = captured.undo("u1", saved.capture().ref());

        assertFalse(undone.done());
        assertEquals("Not undone: it has changed since, and now reads: The owner's phone is a Pixel 9.", undone.text());
        assertEquals(Map.of("phone", "The owner's phone is a Pixel 9."), facts(memory));
        // Mutation: compare the words -> the fact memory_manage wrote is deleted.
    }

    @Test
    @DisplayName("a correction replaces only the fact it corrected: one changed meanwhile is left as it is")
    void aCorrectionOfAChangedFact() throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var memory = new SqliteAgentMemory(jdbc);
        var captured = new CapturedFacts(jdbc, memory, null);
        memory.storeFact("u1", "host_3", "192.0.2.3 is the garage.");
        long corrected = CapturedFacts.id(memory.getFacts("u1").getFirst());
        memory.storeFact("u1", "host_3", "192.0.2.3 is the workshop.");   // meanwhile

        assertNull(captured.replace("u1", "s1", null, "host_3", corrected, "192.0.2.3 is the garden house.", ROW));
        assertEquals(Map.of("host_3", "192.0.2.3 is the workshop."), facts(memory));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM fact_captures", Integer.class), "nothing recorded");
    }

    @Test
    @DisplayName("a new fact never takes a key a fact has, written so or made plain; one with no Latin key is keyed by its ref")
    void keysOfNewFacts() throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var memory = new SqliteAgentMemory(jdbc);
        var captured = new CapturedFacts(jdbc, memory, null);
        memory.storeFact("u1", "NAS-Address", "The NAS is at 192.0.2.20.");
        var a = captured.write("u1", "s1", null, CapturedFacts.Source.OWNER, "nas address", "The NAS is in the attic.",
                false, ROW);
        var b = captured.write("u1", "s1", null, CapturedFacts.Source.OWNER, "nas_address", "The NAS has two disks.",
                false, ROW);
        var c = captured.write("u1", "s1", null, CapturedFacts.Source.OWNER, "телефон", "Телефон Яны — iPhone 15.",
                false, ROW);
        assertEquals("nas_address_2", a.capture().key(), "NAS-Address made plain is nas_address");
        assertEquals("nas_address_3", b.capture().key());
        assertEquals("fact_" + c.capture().ref(), c.capture().key());
        assertEquals("The NAS is at 192.0.2.20.", facts(memory).get("NAS-Address"));
    }

    @Test
    @DisplayName("a fact on offer is not kept until kept, under a free key; refs are the user's own; no row, no capture")
    void offersRefsAndRows() throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var memory = new SqliteAgentMemory(jdbc);
        var captured = new CapturedFacts(jdbc, memory, null);
        var offer = captured.write("u1", "s1", null, CapturedFacts.Source.ANSWER, "supervisor",
                "The Supervisor answers at http://supervisor/core/api.", true, ROW);
        assertEquals(Map.of(), facts(memory));
        memory.storeFact("u1", "supervisor", "The Supervisor needs a token.");   // the key is taken meanwhile
        assertEquals("There is no such fact to keep.", captured.keep("u2", offer.capture().ref()).text());
        assertTrue(captured.keep("u1", offer.capture().ref()).done());
        assertEquals("The Supervisor answers at http://supervisor/core/api.", facts(memory).get("supervisor_2"));
        assertEquals("The Supervisor needs a token.", facts(memory).get("supervisor"), "the fact under the key stays");

        assertNull(captured.write("u1", "gone", null, CapturedFacts.Source.OWNER, "x", "The lamp is a Hue.", false,
                c -> null));
        assertFalse(facts(memory).containsValue("The lamp is a Hue."), "a fact with no row to undo it is not kept");
    }
}
