package com.ownclaw.agent.memory;

import com.ownclaw.conversation.ConversationService;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The facts the local model found in a chat turn ({@code FactCapture}). One the owner himself
 * stated is kept at once, as it was written and with what it replaced, so he can take it back
 * ({@link #undo}). One found only in the answer he was given is kept only when he says so
 * ({@link #keep}): an answer can quote a page or a mail, and what they say is not his to keep.
 * <p>
 * A kept fact lives where every fact does ({@link AgentMemory}), under its key: a task reads it
 * as it reads one the cloud stored with memory_manage, and memory_manage lists and deletes it.
 * This is the record of where it came from, of what may be undone, and of what is on offer. Each
 * is named by an opaque ref -- the chat's buttons and the commands name it so -- never by a number
 * another account's could be counted by.
 * <p>
 * Every change is one transaction that begins with a write, so it holds the database's write
 * lock before it reads anything: begun with a read, another connection's commit in between made
 * its write fail (SQLite's BUSY_SNAPSHOT), and the fact or its record was lost. One that finds
 * the database busy is made again.
 */
@Component
public class CapturedFacts {

    /**
     * Where a fact came from: what the owner stated, as the code found ({@code FactCapture}), or
     * not shown to be his -- the answer's, or his but not plainly -- and kept only by his choice.
     */
    public enum Source {
        OWNER, ANSWER;

        String stored() { return name().toLowerCase(Locale.ROOT); }

        static Source of(String stored) { return "owner".equals(stored) ? OWNER : ANSWER; }
    }

    /**
     * One fact found in a turn.
     *
     * @param state    "kept", "offered" (found in an answer, waiting for the owner) or "undone"
     * @param previous what its key held before it was kept, or null
     * @param memoryId the agent_memory row it wrote, while kept: what an undo compares with
     */
    public record Capture(String ref, String userId, String sessionId, String taskId, Source source,
                          String state, String key, String fact, String previous, Long memoryId) {
        public boolean kept() { return "kept".equals(state); }
        public boolean offered() { return "offered".equals(state); }
    }

    /** What a keep or an undo did, in words for the owner; {@code done} when it was done. */
    public record Done(boolean done, String text) {}

    /** A capture written, with when its chat row was saved. */
    public record Saved(Capture capture, String savedAt) {}

    /**
     * Saves the chat's row for a capture inside the capture's own transaction: when it was saved,
     * or null when it could not be -- then nothing of the capture is kept, so there is never a fact
     * the owner has no row to undo.
     */
    @FunctionalInterface
    public interface Row {
        String save(Capture capture);
    }

    private final JdbcTemplate jdbc;
    private final AgentMemory memory;
    private final ConversationService conversations;
    private final TransactionTemplate transaction;
    private final SecureRandom random = new SecureRandom();

    /** For a test of a commit made meanwhile: run inside each change, right after its first write. */
    Runnable afterFirstWrite = () -> {};

    /** How long a change is made again while the database is busy: a timeout, not a bound on what is kept. */
    private static final long BUSY_FOR_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(1);

    /** @param conversations where the chat's row is marked kept or undone; null where there is no chat */
    public CapturedFacts(JdbcTemplate jdbc, AgentMemory memory, ConversationService conversations) {
        this.jdbc = jdbc;
        this.memory = memory;
        this.conversations = conversations;
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    /**
     * A key as facts are kept under: lowercase letters and digits joined by underscores, accents
     * taken off -- "Garáž kotel" is garaz_kotel. Empty when nothing of it is a letter or a digit of
     * the Latin alphabet: such a fact is keyed by its ref ({@link #write}).
     */
    public static String plainKey(String written) {
        if (written == null) return "";
        String plain = Normalizer.normalize(written, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return plain.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    /**
     * The key of {@code facts} that {@code written} names: one written so exactly first -- a key
     * memory_manage stored, "NAS-Address", is kept as it was given -- then one that is the same
     * once both are made plain ({@link #plainKey}). Null when it names none.
     */
    public static String named(String written, List<AgentMemory.MemoryEntry> facts) {
        if (written == null || written.isBlank()) return null;
        for (var e : facts) if (written.strip().equals(keyOf(e))) return keyOf(e);
        String plain = plainKey(written);
        if (plain.isEmpty()) return null;
        for (var e : facts) if (plain.equals(plainKey(keyOf(e)))) return keyOf(e);
        return null;
    }

    /** The key a fact is kept under. */
    public static String keyOf(AgentMemory.MemoryEntry fact) {
        return String.join(",", fact.tags());
    }

    /** The fact of {@code facts} under exactly {@code key}, or null. */
    public static AgentMemory.MemoryEntry under(String key, List<AgentMemory.MemoryEntry> facts) {
        for (var e : facts) if (key.equals(keyOf(e))) return e;
        return null;
    }

    /** The agent_memory row a fact is, which a change of it -- even back to the same words -- replaces. */
    public static long id(AgentMemory.MemoryEntry e) {
        return Long.parseLong(e.id());
    }

    /**
     * Keep a new fact, or offer one found in an answer ({@code offer}): under {@code key} made plain,
     * or when that names a fact already -- written so, or the same made plain -- under the first of
     * key_2, key_3 ... that does not; under fact_<ref> when the key is empty. A new fact never takes
     * another's key: a key the model happened to reuse wrote "Jana's phone is an iPhone 15" over
     * "The owner's phone is a Pixel 9". Null when the chat's row could not be saved.
     */
    public Saved write(String userId, String sessionId, String taskId, Source source, String key, String fact,
                       boolean offer, Row row) {
        String state = offer ? "offered" : "kept";
        return changing(() -> {
            String ref = insert(userId, sessionId, taskId, source, state, "-", fact);
            afterFirstWrite.run();
            String plain = plainKey(key);
            String chosen = free(plain.isEmpty() ? "fact_" + ref : plain, memory.getFacts(userId));
            jdbc.update("UPDATE fact_captures SET fact_key = ? WHERE ref = ?", chosen, ref);
            Long memoryId = null;
            if (!offer) {
                memory.storeFact(userId, chosen, fact);
                memoryId = rowOf(userId, chosen);
                jdbc.update("UPDATE fact_captures SET memory_id = ? WHERE ref = ?", memoryId, ref);
            }
            return saved(new Capture(ref, userId, sessionId, taskId, source, state, chosen, fact, null, memoryId), row);
        });
    }

    /**
     * Keep {@code fact} in place of the fact under {@code key}, which the owner corrected -- only if
     * that fact is still the one he corrected ({@code expectedId}, its agent_memory row): changed
     * since, by memory_manage or another turn, nothing is replaced. Null then, or when the chat's row
     * could not be saved.
     */
    public Saved replace(String userId, String sessionId, String taskId, String key, long expectedId, String fact,
                         Row row) {
        return changing(() -> {
            String ref = insert(userId, sessionId, taskId, Source.OWNER, "kept", key, fact);
            afterFirstWrite.run();
            AgentMemory.MemoryEntry now = under(key, memory.getFacts(userId));
            if (now == null || id(now) != expectedId) throw new Abort();
            memory.storeFact(userId, key, fact);
            long memoryId = rowOf(userId, key);
            jdbc.update("UPDATE fact_captures SET previous = ?, memory_id = ? WHERE ref = ?", now.content(), memoryId, ref);
            return saved(new Capture(ref, userId, sessionId, taskId, Source.OWNER, "kept", key, fact,
                    now.content(), memoryId), row);
        });
    }

    /**
     * Offer a correction the owner may make: {@code fact} in place of the fact under {@code key} --
     * its row {@code expectedId}, which said {@code previous} -- made only when he keeps it, and
     * only if that fact is still the one it would replace. Null when the chat's row could not be
     * saved.
     */
    public Saved offerCorrection(String userId, String sessionId, String taskId, String key, long expectedId,
                                 String previous, String fact, Row row) {
        return changing(() -> {
            String ref = insert(userId, sessionId, taskId, Source.ANSWER, "offered", key, fact);
            afterFirstWrite.run();
            jdbc.update("UPDATE fact_captures SET previous = ?, replaces_id = ? WHERE ref = ?", previous, expectedId, ref);
            return saved(new Capture(ref, userId, sessionId, taskId, Source.ANSWER, "offered", key, fact, previous,
                    null), row);
        });
    }

    /**
     * Keep a fact on offer, as the owner asked: a new one under its key, or under the first free
     * key_2 ... when a fact took that key meanwhile; a correction in place of the fact it would
     * replace, only if that is still the fact it was offered against. Only the owner's own offer,
     * and once.
     */
    public Done keep(String userId, String ref) {
        Done done = changing(() -> {
            if (!lock(userId, ref)) return new Done(false, "There is no such fact to keep.");
            Capture c = find(userId, ref);
            if (c.kept()) return new Done(false, "Already kept: " + c.fact());
            if (!c.offered()) return new Done(false, "It was taken back, so it is not kept.");
            Long replaces = jdbc.queryForObject("SELECT replaces_id FROM fact_captures WHERE ref = ?", Long.class, ref);
            String key;
            if (replaces != null) {
                AgentMemory.MemoryEntry now = under(c.key(), memory.getFacts(userId));
                if (now == null) return new Done(false, "Not kept: the fact it would replace has been deleted since.");
                if (id(now) != replaces) {
                    return new Done(false, "Not kept: the fact it would replace has changed since, and now reads: "
                            + now.content());
                }
                key = c.key();
            } else {
                key = free(c.key(), memory.getFacts(userId));
            }
            memory.storeFact(userId, key, c.fact());
            long memoryId = rowOf(userId, key);
            jdbc.update("UPDATE fact_captures SET state = 'kept', fact_key = ?, memory_id = ?, "
                    + "decided_at = datetime('now') WHERE ref = ? AND user_id = ?", key, memoryId, ref, userId);
            if (conversations != null) conversations.markFact(userId, ref, "kept");
            return new Done(true, "Kept: " + c.fact());
        });
        return done == null ? new Done(false, "It could not be kept just now. Try again.") : done;
    }

    /**
     * Take back a kept fact: a new one is forgotten, a corrected one is what it was before -- and the
     * chat's row for it says so. Refused when the fact has changed since -- by a later turn or by
     * memory_manage, even back to the same words, which is another row: undone, that change would be
     * thrown away with it. One on offer was never kept. Only the owner's own.
     */
    public Done undo(String userId, String ref) {
        Done done = changing(() -> {
            if (!lock(userId, ref)) return new Done(false, "There is no such remembered fact.");
            Capture c = find(userId, ref);
            if ("undone".equals(c.state())) return new Done(false, "Already undone: " + c.fact());
            if (c.offered()) return new Done(false, "It was never kept, so there is nothing to undo.");
            AgentMemory.MemoryEntry now = under(c.key(), memory.getFacts(userId));
            if (now == null) return new Done(false, "Not undone: it has been deleted since.");
            if (c.memoryId() == null || id(now) != c.memoryId()) {
                return new Done(false, "Not undone: it has changed since, and now reads: " + now.content());
            }
            if (c.previous() == null) memory.deleteFact(userId, c.key());
            else memory.storeFact(userId, c.key(), c.previous());
            jdbc.update("UPDATE fact_captures SET state = 'undone', decided_at = datetime('now') "
                    + "WHERE ref = ? AND user_id = ?", ref, userId);
            if (conversations != null) conversations.markFact(userId, ref, "undone");
            return new Done(true, c.previous() == null ? "Forgotten: " + c.fact()
                    : "Back to what it was: " + c.previous());
        });
        return done == null ? new Done(false, "It could not be undone just now. Try again.") : done;
    }

    /** Capture {@code ref} of {@code userId}, or null. */
    public Capture find(String userId, String ref) {
        List<Capture> found = jdbc.query("SELECT ref, user_id, session_id, task_id, source, state, fact_key, fact, "
                        + "previous, memory_id FROM fact_captures WHERE ref = ? AND user_id = ?",
                (rs, n) -> new Capture(rs.getString("ref"), rs.getString("user_id"), rs.getString("session_id"),
                        rs.getString("task_id"), Source.of(rs.getString("source")), rs.getString("state"),
                        rs.getString("fact_key"), rs.getString("fact"), rs.getString("previous"),
                        rs.getObject("memory_id") == null ? null : rs.getLong("memory_id")),
                ref, userId);
        return found.isEmpty() ? null : found.getFirst();
    }

    /** The facts on offer to {@code userId} now, not yet kept: one is not offered twice. */
    public List<String> offered(String userId) {
        return jdbc.queryForList("SELECT fact FROM fact_captures WHERE user_id = ? AND state = 'offered'",
                String.class, userId);
    }

    /**
     * Where each of {@code facts} came from, by key: the source of the capture that wrote it as it
     * is now. A fact no capture wrote -- memory_manage's -- is not among them.
     */
    public Map<String, Source> sources(String userId, List<AgentMemory.MemoryEntry> facts) {
        var byRow = new HashMap<Long, Source>();
        jdbc.query("SELECT memory_id, source FROM fact_captures WHERE user_id = ? AND state = 'kept' "
                        + "AND memory_id IS NOT NULL",
                rs -> { byRow.put(rs.getLong("memory_id"), Source.of(rs.getString("source"))); }, userId);
        var out = new HashMap<String, Source>();
        for (var f : facts) {
            Source s = byRow.get(id(f));
            if (s != null) out.put(keyOf(f), s);
        }
        return out;
    }

    // ── Private helpers ──

    /** Thrown inside a change to end it with nothing written. */
    private static final class Abort extends RuntimeException {
        Abort() {
            super(null, null, false, false);
        }
    }

    /**
     * {@code work} as one transaction, made again while the database is busy; null when it ended
     * itself ({@link Abort}) and nothing was written.
     */
    private <T> T changing(Supplier<T> work) {
        long until = System.nanoTime() + BUSY_FOR_NANOS;
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.execute(status -> work.get());
            } catch (Abort nothing) {
                return null;
            } catch (DataAccessException e) {
                if (!busy(e) || System.nanoTime() > until) throw e;
                try {
                    Thread.sleep(Math.min(1_000, 50L * attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** Whether a failure is SQLite's "busy" or "locked", which the same change made again gets past. */
    private static boolean busy(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = String.valueOf(t.getMessage());
            if (m.contains("SQLITE_BUSY") || m.contains("SQLITE_LOCKED") || m.contains("database is locked")) return true;
        }
        return false;
    }

    /** The first write of a change to capture {@code ref}: true when it is this user's. */
    private boolean lock(String userId, String ref) {
        int rows = jdbc.update("UPDATE fact_captures SET decided_at = decided_at WHERE ref = ? AND user_id = ?",
                ref, userId);
        afterFirstWrite.run();
        return rows > 0;
    }

    /** A capture's row, written first; its ref, new and opaque. */
    private String insert(String userId, String sessionId, String taskId, Source source, String state, String key,
                          String fact) {
        for (;;) {
            String ref = newRef();
            try {
                jdbc.update("INSERT INTO fact_captures (ref, user_id, session_id, task_id, source, state, fact_key, fact) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", ref, userId, sessionId, taskId, source.stored(), state, key, fact);
                return ref;
            } catch (DuplicateKeyException taken) {
                // Another capture has this ref: another is drawn.
            }
        }
    }

    /** Ten letters and digits, drawn at random: a ref nothing can be guessed or counted from. */
    private String newRef() {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        var sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return sb.toString();
    }

    /** The capture written and its chat row saved, or nothing at all when the row could not be. */
    private static Saved saved(Capture capture, Row row) {
        String savedAt = row.save(capture);
        if (savedAt == null) throw new Abort();
        return new Saved(capture, savedAt);
    }

    /** {@code key}, or the first of key_2, key_3 ... that no fact of {@code facts} has, written so or made plain. */
    private static String free(String key, List<AgentMemory.MemoryEntry> facts) {
        String chosen = key;
        for (int n = 2; taken(chosen, facts); n++) chosen = key + "_" + n;
        return chosen;
    }

    private static boolean taken(String key, List<AgentMemory.MemoryEntry> facts) {
        for (var e : facts) {
            if (key.equals(keyOf(e)) || key.equals(plainKey(keyOf(e)))) return true;
        }
        return false;
    }

    /** The agent_memory row that holds {@code key} of {@code userId} now. */
    private long rowOf(String userId, String key) {
        AgentMemory.MemoryEntry e = under(key, memory.getFacts(userId));
        if (e == null) throw new IllegalStateException("a fact just stored is not there");
        return id(e);
    }
}
