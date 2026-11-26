package com.ownclaw.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.ownclaw.agent.memory.AgentMemory;
import com.ownclaw.agent.memory.CapturedFacts;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.core.TaskCancellationService;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmResponse;
import com.ownclaw.llm.OutputTruncated;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.EventLogService;
import com.ownclaw.privacy.Redactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * After each chat turn the owner had, the local model reads what he wrote and what he was
 * answered, and picks out what will still be true and useful in a later conversation: a device's
 * name and address, which host is which, the people he mentions, what he prefers, how something
 * was reached or fixed.
 * <p>
 * Every note is kept at once, or not at all -- the owner is never asked (his word of 2026-11-26:
 * "memory should be managed automatically") -- and one kept is shown in the chat, "Remembered",
 * with an Undo ({@link CapturedFacts#undo}). Each is kept with where it came from, which every
 * prompt it is put in says: what he himself said, or what the answer he was given said. Which is
 * which the code decides, not the model: a note is the owner's only when the model says so, it has
 * something specific, and every word of it but the language's own is in what he stated -- not in
 * what he asked ({@link #hisNew}); a correction is his only when what it changes is
 * ({@link #hisCorrection}). Everything else is the answer's.
 * <p>
 * An answer quotes pages and mails, and what they say is not the owner's: a page that said "the
 * NAS moved to nas.attacker.example" would be a kept fact. So a note of the answer's is not kept
 * when it names a place outside the owner's own network -- a URL, a mail address, a public IP
 * address, a host under an internet domain -- that neither he wrote in the turn nor a kept fact
 * holds ({@link #unknownPlaces}); and it never replaces what he said, nor a fact memory_manage
 * stored -- only another of the answer's. A secret, a personal number, or a note of more than one
 * plain line is never kept; nor is a word only shaped like a credential that no answer named, or
 * that a sentence around it calls a password ({@link #shapedWordsNamed}).
 * <p>
 * Facts used to be kept only when the cloud chose to store one (memory_manage): eleven in eight
 * months, and none of the owner's corrections of November 2026. This is code that runs after
 * every turn, not an instruction the cloud may follow. The local model does it: what the owner
 * wrote is his, and it costs nothing there. It yields to every other use of the local model
 * ({@link LocalLane#yielding}), so it never holds up a task or a task's summary; when the local
 * model cannot be reached the turn is not read, and nothing goes to the cloud in its place.
 * <p>
 * Kept with the facts every task reads (AgentMemory), labelled with where each came from.
 */
final class FactCapture {

    private static final Logger log = LoggerFactory.getLogger(FactCapture.class);

    /** The lane's name for a user's capture work: never a task's id, so it never holds the lane. */
    static final String LANE = "facts:";

    /** The command that takes a remembered fact back, as Telegram makes a link of it. */
    static String undoCommand(String ref) {
        return "/forget_" + ref;
    }

    /** The command that keeps a fact on offer, as Telegram makes a link of it. */
    static String keepCommand(String ref) {
        return "/keep_" + ref;
    }

    /** What the status data carries the undo command as, for Telegram (TelegramBotService). */
    static final String UNDO = "undo";
    /** What it carries the keep command as. */
    static final String KEEP = "keep";

    /**
     * A chat turn that has ended.
     *
     * @param said   what the owner wrote in it, in order: his message, then those the task read
     *               while it worked
     * @param answer what he was answered, as the cloud may read it: a private answer is the note
     *               that it exists (AgentLoop#turnEnded)
     * @param worked whether the task did what was asked
     * @param images how many images the owner pasted with his message: the local model reading the
     *               turn cannot see them, and is told they were there
     */
    record Turn(String userId, String sessionId, String taskId, List<String> said, String answer,
                boolean worked, TaskChat.Channel channel, int images) {

        /** A turn whose message came with no pasted image. */
        Turn(String userId, String sessionId, String taskId, List<String> said, String answer, boolean worked,
             TaskChat.Channel channel) {
            this(userId, sessionId, taskId, said, answer, worked, channel, 0);
        }
    }

    /**
     * One fact the local model would keep, under {@code key}.
     *
     * @param byOwner  whether the model says the owner said it -- checked against what he wrote
     * @param replaces the key of the kept fact it corrects, as the model wrote it, or null
     */
    record Note(String key, String fact, boolean byOwner, String replaces) {}

    private final LocalLane lane;
    private final Supplier<LlmProvider> local;
    private final CapturedFacts captured;
    private final AgentMemory memory;
    /** The user's secret vault values; null when the vault could not be read -- then nothing is kept. */
    private final Function<String, Map<String, String>> vault;
    private final ConversationService conversations;
    private final ChatStatusEmitter emitter;
    private final EventLogService events;
    /** Set as the application stops: nothing more is read, and the read under way ends. */
    private volatile boolean stopped;

    FactCapture(LocalLane lane, Supplier<LlmProvider> local, CapturedFacts captured, AgentMemory memory,
                Function<String, Map<String, String>> vault, ConversationService conversations,
                ChatStatusEmitter emitter, EventLogService events) {
        this.lane = lane;
        this.local = local;
        this.captured = captured;
        this.memory = memory;
        this.vault = vault;
        this.conversations = conversations;
        this.emitter = emitter;
        this.events = events;
    }

    /**
     * Read {@code turn} for facts to keep, as background work of its user's: after the turns of
     * that user read before it, once nothing else needs the local model.
     */
    void after(Turn turn) {
        if (stopped || turn == null || turn.said().isEmpty() || turn.channel() == TaskChat.Channel.OPS) return;
        lane.background(LANE + turn.userId(), () -> {
            try {
                read(turn);
            } catch (RuntimeException e) {
                // Its type only: a failure here can quote what the owner said.
                log.warn("A chat turn of {} was not read for facts: {}", turn.userId(), e.getClass().getSimpleName());
            }
        });
    }

    /** The application stops: no turn is read from now on, and the read under way ends. */
    void stop() {
        stopped = true;
        lane.interruptStopped();
    }

    /** The turn read by the local model, and what it found kept or refused. */
    void read(Turn turn) {
        if (stopped) return;
        LlmProvider model = local.get();
        if (model == null || !model.isAvailable()) {
            log.info("A chat turn of {} was not read for facts: the local model is not answering.", turn.userId());
            return;
        }
        Map<String, String> secrets = vault.apply(turn.userId());
        if (secrets == null) {
            // Unchecked against the vault, a fact could be one of its secrets.
            log.warn("A chat turn of {} was not read for facts: the vault could not be read.", turn.userId());
            return;
        }
        List<AgentMemory.MemoryEntry> kept = memory.getFacts(turn.userId());
        LlmResponse reply;
        try {
            reply = lane.yielding(model, prompt(turn, kept, captured.sources(turn.userId(), kept)), true,
                    LANE + turn.userId(), () -> stopped);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (TaskCancellationService.TaskCancelledException ended) {
            return;   // the application stops
        } catch (OutputTruncated full) {
            // Not read again: the same turn would run on the same way.
            log.warn("A chat turn of {} was not read for facts: {}.", turn.userId(), full.replyRanOn()
                    ? "the local model's reply ran on until its window was full"
                    : "it does not fit the local model's window");
            return;
        }
        var known = new HashSet<String>();
        for (var e : kept) known.add(same(e.content()));
        // Made before 2026-11-26 and still on offer: the owner can keep it, so it is not kept beside it.
        for (String offered : captured.offered(turn.userId())) known.add(same(offered));
        int done = 0;
        for (Note note : notes(reply.content())) {
            if (keep(turn, note, secrets, known)) done++;
        }
        if (done > 0 && events != null) {
            events.info(turn.userId(), turn.taskId(), "facts.found",
                    "Kept " + done + (done == 1 ? " fact" : " facts") + " from a chat turn");
        }
    }

    /** {@code note} kept, and shown; whether it was. */
    private boolean keep(Turn turn, Note note, Map<String, String> secrets, Set<String> known) {
        String fact = note.fact();
        if (structured(fact)) {
            log.info("A fact of a chat turn of {} was refused: it is not one line of plain text.", turn.userId());
            return false;
        }
        Verdict verdict = worse(secret(fact, secrets), secretKey(note.key(), secrets));
        if (verdict == Verdict.REFUSE) {
            log.info("A fact of a chat turn of {} was refused: it holds a secret or a personal number.", turn.userId());
            return false;
        }
        // A word only shaped like a credential -- rpi4-kitchen, RT-AX88U, sunflower77 -- is as often
        // a host's name as a password: kept when the answer named it too, and nothing around it says
        // it is a password.
        if (verdict == Verdict.SHAPE && !shapedWordsNamed(fact + " " + spaced(note.key()), turn)) {
            log.info("A fact of a chat turn of {} was not kept: a word of it is shaped like a credential, and no "
                    + "answer named it as anything else.", turn.userId());
            return false;
        }
        // Kept already, or still on offer, under any key: the same words, case and blanks aside.
        if (!known.add(same(fact))) return false;
        List<String> statements = statements(turn.said());
        List<AgentMemory.MemoryEntry> facts = memory.getFacts(turn.userId());
        String corrected = CapturedFacts.named(note.replaces(), facts);
        var shown = new Shown();
        CapturedFacts.Saved saved;
        if (corrected != null) {
            AgentMemory.MemoryEntry old = CapturedFacts.under(corrected, facts);
            if (note.byOwner() && hisCorrection(fact, old.content(), statements)) {
                saved = captured.replace(turn.userId(), turn.sessionId(), turn.taskId(), corrected, CapturedFacts.id(old),
                        fact, c -> row(turn, c, shown));
            } else if (captured.sources(turn.userId(), List.of(old)).get(corrected) != CapturedFacts.Source.ANSWER) {
                // Not shown to be his -- a swap, a deletion, a "never" taken out, or the answer's -- and
                // what it would change is what he said, or what memory_manage stored: that stands.
                log.info("A correction of a chat turn of {} was not made: what it changes is not an answer's.",
                        turn.userId());
                return false;
            } else if (!unknownPlaces(fact, turn.said(), facts).isEmpty()) {
                log.info("A correction of a chat turn of {} was not made: it names a place outside the owner's "
                        + "network that only the answer named.", turn.userId());
                return false;
            } else {
                saved = captured.replace(turn.userId(), turn.sessionId(), turn.taskId(), CapturedFacts.Source.ANSWER,
                        corrected, CapturedFacts.id(old), fact, c -> row(turn, c, shown));
            }
        } else {
            boolean his = note.byOwner() && hisNew(fact, statements);
            if (!his && !unknownPlaces(fact, turn.said(), facts).isEmpty()) {
                log.info("A fact of a chat turn of {} was not kept: it names a place outside the owner's network "
                        + "that only the answer named.", turn.userId());
                return false;
            }
            saved = captured.write(turn.userId(), turn.sessionId(), turn.taskId(),
                    his ? CapturedFacts.Source.OWNER : CapturedFacts.Source.ANSWER, note.key(), fact, false,
                    c -> row(turn, c, shown));
        }
        if (saved == null) return false;
        show(turn, saved, shown);
        return true;
    }

    /** A key's parts as the words they are: guest_wifi_sunflower77 is "guest wifi sunflower77". */
    private static String spaced(String key) {
        return key == null ? "" : key.replaceAll("[_\\-.\\s]+", " ");
    }

    /** What a row was saved with, for the page and Telegram once its capture is committed. */
    private static final class Shown {
        String line;
        String ownerText;
        Map<String, Object> header;
    }

    /**
     * The chat's row for a capture, saved in the chat of the turn -- without moving the chat up the
     * list ({@link ConversationService#saveFactRow}): "Remembered" and the fact, with what it
     * replaced. (One on offer, as was made before 2026-11-26, said "Remember this?" and what it
     * would replace.) Its text for anything but the owner's own screens says only which. Null -- and then nothing of the capture is kept -- when
     * the turn has no chat, or its chat is gone.
     */
    private String row(Turn turn, CapturedFacts.Capture c, Shown shown) {
        if (turn.sessionId() == null) return null;
        String emoji = TaskChat.Actor.LOCAL.emoji;
        shown.line = emoji + (c.offered() ? " Remember this?" : " Remembered");
        shown.ownerText = shown.line + "\n\n" + c.fact() + (c.previous() == null ? ""
                : (c.offered() ? "\n\nIt would replace: " : "\n\nIt replaces: ") + c.previous());
        var header = new LinkedHashMap<String, Object>();
        header.put("actor", TaskChat.Actor.LOCAL.id);
        header.put("kind", c.offered() ? "offered" : "remembered");
        header.put("ref", c.ref());
        shown.header = header;
        return conversations.saveFactRow(turn.userId(), turn.sessionId(), shown.line, turn.taskId(),
                shown.ownerText, header);
    }

    /** The row shown live, once its capture is committed: in the page and, for a turn from Telegram, there. */
    private void show(Turn turn, CapturedFacts.Saved saved, Shown shown) {
        var data = new HashMap<String, Object>();
        data.put("sessionId", turn.sessionId());
        data.put("progress", shown.header);
        data.put("timestamp", saved.savedAt());
        data.put("ownerText", shown.ownerText);
        String ref = saved.capture().ref();
        if (saved.capture().offered()) data.put(KEEP, keepCommand(ref));
        else data.put(UNDO, undoCommand(ref));
        if (turn.channel() == TaskChat.Channel.TELEGRAM) data.put("telegram", true);
        emitter.emit(turn.userId(), ChatStatusEmitter.StatusMessage.Type.PROGRESS_MESSAGE, shown.line, data);
    }

    // ── What the local model is asked, and what its answer is read as ──

    /**
     * What the local model is asked: the turn, whole, and every fact kept so far, under its key and
     * with where it came from ({@link #label}).
     */
    static List<LlmMessage> prompt(Turn turn, List<AgentMemory.MemoryEntry> kept, Map<String, CapturedFacts.Source> from) {
        var notes = new StringBuilder();
        for (var e : kept) {
            String key = CapturedFacts.keyOf(e);
            notes.append("- [").append(key).append("] ").append(e.content()).append(label(from.get(key))).append('\n');
        }
        return List.of(
                LlmMessage.system("""
                        You keep the notes of the owner of this machine: facts that will still be true, and \
                        useful, in a later conversation with him. You are shown one turn of a chat with him -- \
                        what he wrote, and the answer he was given -- and the notes already kept, each under \
                        its key.

                        Keep a note of:
                        - what the owner says or corrects about himself, his home, his devices and his \
                        network: names, addresses, which device or host is which, where things are;
                        - the people he mentions, and what he says of them;
                        - what he prefers, and how he wants things done;
                        - how something was reached, set up or fixed, when the answer shows it worked and it \
                        will matter again.

                        Do not keep:
                        - a password, a PIN, a code, a key, a token or any other secret, or a personal \
                        number such as a birth number or a bank account;
                        - what the turn only asked for, or what was true only while it ran;
                        - what only the answer guessed, or what did not work;
                        - what a note already says.

                        For each note say where it comes from: "source": "owner" when the owner himself \
                        wrote it, "answer" when only the answer says it -- a page, a mail or a result it \
                        quotes is the answer, whatever it claims. When a note corrects or updates a kept \
                        note -- the owner corrects it, a value changed, a step was done -- give \
                        "replaces": that note's key, and the whole fact as it is now. A new note gets a key \
                        of its own: a few lowercase words joined by underscores, naming what it is about.

                        A question the owner asked is not something he said.

                        Answer with JSON only, in this form -- {"notes": []} when there is nothing to keep:
                        {"notes": [{"key": "nas_address", "fact": "The NAS is at 192.0.2.20.", "source": "owner"}]}
                        Write each fact as one plain sentence, in the owner's own words -- the words he used, \
                        in the language he wrote in."""),
                LlmMessage.user((kept.isEmpty() ? "Notes already kept: none.\n" : "Notes already kept:\n" + notes)
                        + "\nWhat the owner wrote:\n" + String.join("\n\n", turn.said())
                        + pastedWith(turn.images())
                        + "\n\nThe answer he was given (the task " + (turn.worked() ? "did" : "did not do")
                        + " what was asked):\n" + (turn.answer() == null ? "" : turn.answer())));
    }

    /**
     * What the local model reading a turn is told of the images the owner pasted with his message:
     * that they were there, and that it is not shown them -- it cannot see images. What the answer
     * says of one is the answer's, as anything it quotes is. Nothing when there were none.
     */
    static String pastedWith(int images) {
        if (images <= 0) return "";
        return "\n[He pasted " + (images == 1 ? "an image" : images + " images") + " with it, which you are not "
                + "shown: " + com.ownclaw.llm.OllamaProvider.CANNOT_SEE_IMAGES + ".]";
    }

    /** Where a kept fact came from, as a prompt says it after the fact; nothing for memory_manage's. */
    static String label(CapturedFacts.Source source) {
        if (source == null) return "";
        return source == CapturedFacts.Source.OWNER ? " (the owner said this)"
                : " (from an answer he was given, not his own words)";
    }

    /**
     * The notes in the local model's answer: {"notes": [{"key", "fact", "source", "replaces"}]}, or
     * the list alone. A note without a fact is none. Anything else -- no JSON, an object of another
     * shape -- is no note.
     */
    static List<Note> notes(String reply) {
        if (reply == null || reply.isBlank()) return List.of();
        JsonNode root;
        try {
            root = TextCalls.MAPPER.readTree(json(reply));
        } catch (Exception notJson) {
            return List.of();
        }
        JsonNode list = root == null ? null : root.isArray() ? root : root.path("notes");
        if (list == null || !list.isArray()) return List.of();
        var out = new ArrayList<Note>();
        for (JsonNode n : list) {
            String fact = n.path("fact").isTextual() ? n.path("fact").asText().strip() : "";
            if (fact.isEmpty()) continue;
            String key = n.path("key").isTextual() ? n.path("key").asText() : "";
            String replaces = n.path("replaces").isTextual() && !n.path("replaces").asText().isBlank()
                    ? n.path("replaces").asText() : null;
            out.add(new Note(key, fact, "owner".equalsIgnoreCase(n.path("source").asText("").strip()), replaces));
        }
        return out;
    }

    /** The JSON in an answer: from its first brace or bracket to its last, past any fence around it. */
    private static String json(String reply) {
        int object = reply.indexOf('{');
        int array = reply.indexOf('[');
        int start = object < 0 ? array : array < 0 ? object : Math.min(object, array);
        if (start < 0) return reply;
        int end = Math.max(reply.lastIndexOf('}'), reply.lastIndexOf(']'));
        return end > start ? reply.substring(start, end + 1) : reply.substring(start);
    }

    /** A fact as two that say the same are compared: case, blanks and a closing stop aside. */
    private static String same(String fact) {
        return fact == null ? "" : fact.strip().replaceAll("[.!\\s]+$", "").replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    // ── Whose a note is ──

    /** URLs, mail addresses, paths, quoted strings, addresses, hosts, numbers, words with digits. */
    private static final String SPECIFIC_FORMS =
            "\\b[a-z][a-z0-9+.-]*://[^\\s\"'<>]+"                                   // a URL
                    + "|[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)+"     // a mail address
                    + "|(?<![\\p{L}\\p{N}/])(?:~|\\.{1,2})?/[^\\s\"'<>]+"                  // a path
                    + "|\\b[a-z]:\\\\[^\\s\"'<>]+"                                          // a Windows path
                    + "|\"[^\"]+\"|„[^“”]+[“”]|“[^”]+”|‘[^’]+’|«[^»]+»|`[^`]+`"             // quoted
                    + "|(?<![\\p{L}\\p{N}])'[^'\\n]+'(?![\\p{L}\\p{N}])"
                    + "|\\b[0-9a-f]{2}(?:[:-][0-9a-f]{2}){5}\\b"                            // a MAC address
                    + "|\\b[0-9a-f]{4}\\.[0-9a-f]{4}\\.[0-9a-f]{4}\\b"                      // a MAC, dotted
                    + "|\\b(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}\\b"                         // an IPv6 address
                    + "|\\b(?=[\\p{L}\\p{N}-]*\\p{L})[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)+\\b" // a host or a domain
                    + "|\\d+(?:[.,:/]\\d+)*"                                                // a number, an IPv4 address
                    + "|\\b(?=\\p{L}*\\p{N})(?=\\p{N}*\\p{L})[\\p{L}\\p{N}]+\\b";           // a word with digits

    private static final Pattern SPECIFIC = Pattern.compile("(?iu)" + SPECIFIC_FORMS);

    /** What a fact is made of, in order: its specific words, and its words -- "owner's" among them. */
    private static final Pattern ELEMENT = Pattern.compile("(?iu)(" + SPECIFIC_FORMS + ")|(\\p{L}[\\p{L}'’]*)");

    /** A word, with an apostrophe in it: "owner's". */
    private static final Pattern WORD = Pattern.compile("\\p{L}[\\p{L}'’]*");

    /**
     * The language's own words, English and Czech, which a fact may add to what the owner wrote:
     * articles, prepositions, conjunctions, pronouns, "is" and "has", and the owner himself. Never a
     * word that turns what is said around ({@link #NEGATING}), nor one that says what he wants:
     * those must be his.
     */
    private static final Set<String> COMMON = Set.copyOf(List.of(
            "the", "an", "is", "are", "was", "were", "be", "been", "am", "has", "have", "had", "do", "does",
            "did", "of", "in", "on", "at", "to", "for", "from", "with", "by", "as", "and", "or", "but", "it",
            "its", "this", "that", "these", "those", "there", "here", "which", "who", "whose", "he", "she",
            "they", "them", "his", "her", "their", "our", "my", "your", "me", "him", "us", "we", "you",
            "owner", "owners", "user", "also", "into", "than", "so", "via", "both", "each",
            "na", "ve", "do", "ze", "se", "ke", "pro", "za", "po", "od", "při", "přes", "nad", "pod", "mezi",
            "je", "jsou", "byl", "byla", "bylo", "byli", "jsem", "jsme", "jste", "má", "mají", "mám", "máme",
            "ten", "ta", "to", "ty", "ti", "toho", "té", "tom", "tím", "jeho", "její", "jejich", "můj",
            "moje", "mé", "náš", "naše", "svůj", "své", "že", "aby", "který", "která", "které",
            "kterou", "kde", "jako", "majitel", "majitele", "majiteli", "majitelem", "vlastník",
            "vlastníka", "vlastníkovi"));

    /**
     * Words that turn what is said around, make it absolute, or set something aside -- except,
     * instead, kromě, místo, avoid, stop -- in English and Czech: a correction
     * that adds or takes out one is offered, never made ({@link #hisCorrection}), and one the owner
     * wrote before a word a fact keeps must be in the fact too ({@link #hisNew}).
     */
    private static final Set<String> NEGATING = Set.copyOf(List.of(
            "not", "no", "never", "none", "nothing", "nobody", "neither", "nor", "without", "always", "only",
            "must", "every", "all", "cannot", "except", "excluding", "besides", "instead", "rather", "unless",
            "avoid", "avoids", "avoiding", "stop", "stops", "stopping",
            "ne", "nikdy", "vždy", "vždycky", "jen", "jenom", "pouze", "musí", "musíš", "musím", "musíme",
            "musíte", "bez", "žádný", "žádná", "žádné", "nic", "nikdo", "nesmí", "není", "kromě", "vyjma",
            "mimo", "místo", "než", "pokud", "když"));

    private static boolean negating(String element) {
        return NEGATING.contains(element) || element.contains("n't") || element.contains("n’t");
    }

    /** Where a sentence ends: after its stop, or at a line break. */
    private static final Pattern SENTENCE = Pattern.compile("(?<=[.!?…])\\s+|\\R+");

    /** A sentence's first word answering what was said before -- "No," "Ne," -- with its comma: "Ne:" is no answer, it says not. */
    private static final Pattern ANSWERING_NO = Pattern.compile("(?iu)^(?:no|nope|ne)\\s*,\\s*");

    /** A word that makes a sentence a question put as a request, or a condition: "check if", "zjisti, jestli", "pokud". */
    private static final Pattern ASKING = Pattern.compile(
            "(?iu)(?<![\\p{L}])(?:if|whether|jestli|jestliže|zda|zdali|pokud|když)(?![\\p{L}])");

    /**
     * What the owner stated, as what a fact of his may rest on: his sentences, but those that ask --
     * ending with "?", or built on if, whether, jestli, zda, pokud, když: "Is the NAS at 192.0.2.20?"
     * and "Check if the NAS is at 192.0.2.20." say nothing of where it is, "Pokud NAS zamrzne,
     * restartuj ho" nothing of when to restart it.
     */
    static List<String> statements(List<String> said) {
        var out = new ArrayList<String>();
        for (String message : said) {
            for (String sentence : SENTENCE.split(message)) {
                String st = sentence.strip();
                if (st.isEmpty() || st.replaceAll("[\\s\"'”’»)\\]]+$", "").matches("(?s).*[?？]$")) continue;
                if (ASKING.matcher(st).find()) continue;
                // "No, the .3 machine is the garden house": the no answers what was said before, and
                // turns nothing of this sentence around. Only the word, before its comma, at the start.
                out.add(ANSWERING_NO.matcher(st).replaceFirst(""));
            }
        }
        return out;
    }

    /**
     * What of a text must be the owner's, in order: each specific word, and each word but the
     * language's own and single letters, lowercased -- "owner's" is "owner".
     */
    static List<String> elements(String text) {
        var out = new ArrayList<String>();
        Matcher m = ELEMENT.matcher(text);
        while (m.find()) {
            if (m.group(1) != null) {
                out.add(m.group(1).strip().toLowerCase(Locale.ROOT));
                continue;
            }
            String w = m.group(2).replaceAll("['’]s$", "").replaceAll("['’]+$", "").toLowerCase(Locale.ROOT);
            if (w.codePointCount(0, w.length()) > 1 && !COMMON.contains(w)) out.add(w);
        }
        return out;
    }

    /** The specific words of a fact, as written. */
    private static List<String> specific(String fact) {
        var out = new ArrayList<String>();
        Matcher m = SPECIFIC.matcher(fact);
        while (m.find()) out.add(m.group().strip());
        return out;
    }

    /** The names of a fact the owner wrote as names: words with a capital that he wrote so, but the language's own. */
    private static List<String> names(String fact, List<String> statements) {
        String his = String.join("\n", statements);
        var out = new ArrayList<String>();
        Matcher m = WORD.matcher(fact);
        while (m.find()) {
            String w = m.group().replaceAll("['’]s$", "").replaceAll("['’]+$", "");
            if (w.isEmpty() || !Character.isUpperCase(w.codePointAt(0))) continue;
            if (COMMON.contains(w.toLowerCase(Locale.ROOT))) continue;
            if (asWritten(w, his)) out.add(w);
        }
        return out;
    }

    /**
     * Whether a new fact is the owner's: it has something specific -- a number, an address, a host,
     * a URL, a mail address, a path, a quoted string, a word with digits, a name he wrote as one --
     * and it is one sentence of his, its words in his order ({@link #fits}). A fact with nothing
     * specific -- an instruction, a preference -- is where a page's injection does its harm, and is
     * offered, never kept at once. Read as a set of his words, "back up to the USB disk, not the
     * cloud" said "back up to the cloud, not the USB disk", and "do not reboot the NAS at night"
     * said "reboot the NAS at night".
     */
    static boolean hisNew(String fact, List<String> statements) {
        if (specific(fact).isEmpty() && names(fact, statements).isEmpty()) return false;
        List<String> wanted = elements(fact);
        for (String sentence : statements) if (fits(wanted, elements(sentence))) return true;
        return false;
    }

    /**
     * Whether {@code fact} -- what a fact is made of, in order -- is {@code sentence}'s, in its
     * order, and keeps each word of it that turns what it says around: a "not", a "never", an
     * "except" the fact leaves out turns the whole sentence around, wherever it stands -- "reboot
     * the NAS under no circumstances", "do not, under any circumstances, reboot the NAS" -- unless
     * it only sets aside an address or a number the fact leaves out as well: "My NAS is at
     * 192.0.2.20, not .21" says the NAS is at 192.0.2.20.
     */
    static boolean fits(List<String> fact, List<String> sentence) {
        if (fact.isEmpty()) return false;
        var kept = new boolean[sentence.size()];
        int at = 0;
        for (String element : fact) {
            while (at < sentence.size() && !sentence.get(at).equals(element)) at++;
            if (at == sentence.size()) return false;
            kept[at++] = true;
        }
        // A negation of his the fact leaves out turns the whole sentence around -- "reboot the NAS
        // under no circumstances" -- unless it only sets aside an address or a number the fact
        // leaves out too: "my NAS is at 192.0.2.20, not .21".
        for (int i = 0; i < sentence.size(); i++) {
            if (kept[i] || !negating(sentence.get(i))) continue;
            int next = i + 1;
            boolean setsAsideOnly = next < sentence.size() && kind(sentence.get(next)) != null && !kept[next];
            if (!setsAsideOnly) return false;
        }
        return true;
    }

    /**
     * Whether a correction is the owner's: it has something specific, as a new fact must; it adds
     * nor takes out no word that turns what is said around -- not, never, always, only, must,
     * without, nikdy, vždy, jen, bez, a "ne-" --; the words it keeps of the fact it corrects stand
     * in the same order; each address or number it takes out is replaced by one of the same kind;
     * what it adds is his and is something; and the whole of it is one sentence of his
     * ({@link #fits}). A swap, a deletion, a "never" taken out, or one riding on a word of his --
     * the NAS given the printer's address, the router dropped for the printer -- is offered, never
     * made.
     */
    static boolean hisCorrection(String fact, String corrected, List<String> statements) {
        if (specific(fact).isEmpty() && names(fact, statements).isEmpty()) return false;
        List<String> now = elements(fact);
        List<String> was = elements(corrected);
        if (!negations(now).equals(negations(was)) || flipsANe(now, was) || flipsANe(was, now)) return false;
        var added = new ArrayList<>(now);
        for (String e : was) added.remove(e);
        var removed = new ArrayList<>(was);
        for (String e : now) removed.remove(e);
        var both = new HashMap<String, Integer>();
        var counted = counts(now);
        counts(was).forEach((e, n) -> both.put(e, Math.min(n, counted.getOrDefault(e, 0))));
        if (!only(now, both).equals(only(was, both))) return false;   // the same words, in another order or role
        var replacements = new HashMap<String, Integer>();
        for (String e : added) if (kind(e) != null) replacements.merge(kind(e), 1, Integer::sum);
        for (String e : removed) {
            String k = kind(e);
            if (k == null) continue;
            if (replacements.getOrDefault(k, 0) == 0) return false;   // taken out, with nothing of his in its place
            replacements.merge(k, -1, Integer::sum);
        }
        if (added.isEmpty()) return false;
        String his = String.join("\n", statements);
        for (String e : added) if (!among(e, his)) return false;
        // And the whole of it one sentence of his: an address of his is not his for the NAS when he
        // wrote it of the printer -- "The NAS is at 192.0.2.7" after "The printer is at 192.0.2.7".
        for (String sentence : statements) if (fits(now, elements(sentence))) return true;
        return false;
    }

    private static Map<String, Integer> counts(List<String> elements) {
        var out = new HashMap<String, Integer>();
        for (String e : elements) out.merge(e, 1, Integer::sum);
        return out;
    }

    /** {@code elements} in their order, each only as many times as {@code keep} counts it -- its first ones. */
    private static List<String> only(List<String> elements, Map<String, Integer> keep) {
        var left = new HashMap<>(keep);
        var out = new ArrayList<String>();
        for (String e : elements) {
            if (left.getOrDefault(e, 0) > 0) {
                out.add(e);
                left.merge(e, -1, Integer::sum);
            }
        }
        return out;
    }

    /** The words of {@code elements} that turn what is said around, counted. */
    private static Map<String, Integer> negations(List<String> elements) {
        var out = new HashMap<String, Integer>();
        for (String e : elements) if (negating(e)) out.merge(e, 1, Integer::sum);
        return out;
    }

    /** Whether {@code a} has a word {@code b} has with "ne" before it -- "nerestartuj" for "restartuj". */
    private static boolean flipsANe(List<String> a, List<String> b) {
        for (String e : a) {
            if (e.length() > 3 && e.startsWith("ne") && b.contains(e.substring(2))) return true;
        }
        return false;
    }

    /** What kind of specific word an element is -- an IPv4 address, a number, a MAC ... -- or null for a word. */
    private static String kind(String element) {
        if (element.matches("\\d{1,3}(?:\\.\\d{1,3}){3}(?:/\\d{1,2})?(?::\\d{1,5})?")) return "ipv4";
        if (element.matches("\\d+(?:[.,:/]\\d+)*")) return "number";
        if (element.matches("(?i)[0-9a-f]{2}(?:[:-][0-9a-f]{2}){5}|[0-9a-f]{4}\\.[0-9a-f]{4}\\.[0-9a-f]{4}")) return "mac";
        if (element.matches("(?i)[a-z][a-z0-9+.-]*://\\S+")) return "url";
        if (element.contains("@")) return "mail";
        if (element.matches("(?i)(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}")) return "ipv6";
        if (element.startsWith("/") || element.startsWith("~") || element.matches("(?i)[a-z]:\\\\.*")) return "path";
        if (SPECIFIC.matcher(element).matches()) return "other";
        return null;
    }

    /** Whether {@code word} is in {@code text} as a whole word, case and blanks aside. */
    private static boolean among(String word, String text) {
        String w = plain(word);
        String t = plain(text);
        if (w.isEmpty()) return true;
        for (int at = t.indexOf(w); at >= 0; at = t.indexOf(w, at + 1)) {
            int end = at + w.length();
            boolean starts = at == 0 || !wordy(w.charAt(0)) || !wordy(t.charAt(at - 1));
            boolean ends = end == t.length() || !wordy(w.charAt(w.length() - 1)) || !wordy(t.charAt(end));
            if (starts && ends) return true;
        }
        return false;
    }

    /** Whether {@code word} is in {@code text} as a whole word written just so, its capital too. */
    private static boolean asWritten(String word, String text) {
        for (int at = text.indexOf(word); at >= 0; at = text.indexOf(word, at + 1)) {
            int end = at + word.length();
            if ((at == 0 || !wordy(text.charAt(at - 1))) && (end == text.length() || !wordy(text.charAt(end)))) return true;
        }
        return false;
    }

    private static String plain(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static boolean wordy(char ch) {
        return Character.isLetterOrDigit(ch);
    }

    // ── What an answer may not plant ──

    /**
     * Whether every word of {@code text} only shaped like a credential -- eight characters or more
     * mixing letters and digits -- is one the answer named too, as a whole word: what the systems
     * the turn reached report is a host, a model, an entity, not a password the owner typed. And no
     * sentence of the turn it stands in, his or the answer's, calls it a secret: "the guest Wi-Fi
     * password is sunflower77" makes sunflower77 one, whatever the note leaves out. A private answer
     * is the note that it exists (Turn#answer): no word of it is named.
     */
    static boolean shapedWordsNamed(String text, Turn turn) {
        String answer = turn.answer() == null ? "" : turn.answer();
        var sentences = new ArrayList<String>();
        for (String m : turn.said()) sentences.addAll(List.of(SENTENCE.split(m)));
        sentences.addAll(List.of(SENTENCE.split(answer)));
        for (String word : text.split("\\s+")) {
            String w = word.replaceAll("^[\\p{Punct}„“”‘’«»]+|[\\p{Punct}„“”‘’«»]+$", "");
            if (!credentialShaped(w)) continue;
            if (!among(w, answer)) return false;
            for (String sentence : sentences) {
                if (among(w, sentence) && SECRET_WORD.matcher(sentence).find()) return false;
            }
        }
        return true;
    }

    /** A URL: its scheme, then its host -- past a user and a password -- up to its port, path, query or end. */
    private static final Pattern URL_HOST = Pattern.compile(
            "(?i)\\b[a-z][a-z0-9+.-]*://(?:[^\\s/?#@\"'<>]*@)?(\\[[0-9a-f:.]+\\]|[^\\s/:?#\"'<>\\[\\]]+)");

    /** A mail address. */
    private static final Pattern MAIL = Pattern.compile("[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)+");

    /** Four numbers joined by dots, no more: an IPv4 address when each is a byte. */
    private static final Pattern IPV4 = Pattern.compile("(?<![\\p{N}.])(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?!\\p{N}|\\.\\p{N})");

    /** Hex groups joined by colons: an IPv6 address when they make one ({@link #ipv6}) -- a MAC or a time does not. */
    private static final Pattern IPV6 = Pattern.compile("(?i)(?<![0-9a-f:])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?![0-9a-f:])");

    /**
     * A dotted name as a word of its own -- not a piece of a path, of a mail address, of a URL, or of
     * an identifier such as input_number.heating_target: a host's name when its last label is an
     * internet domain ({@link #TOP_LEVEL_DOMAINS}).
     */
    private static final Pattern NAME = Pattern.compile(
            "(?<![\\p{L}\\p{N}_./\\\\@:~-])[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)+(?![\\p{L}\\p{N}_-]|\\.[\\p{L}\\p{N}])");

    /**
     * Home Assistant's entity domains: "light.kitchen" is an entity of its own, not a host -- so a
     * host named so, light.example, is taken for one too, what is left of the guard.
     */
    static final Set<String> ENTITY_DOMAINS = Set.of("air_quality", "alarm_control_panel", "automation", "binary_sensor",
            "button", "calendar", "camera", "climate", "conversation", "counter", "cover", "date", "datetime",
            "device_tracker", "event", "fan", "group", "humidifier", "image", "input_boolean", "input_button",
            "input_datetime", "input_number", "input_select", "input_text", "lawn_mower", "light", "lock", "media_player",
            "notify", "number", "person", "remote", "scene", "schedule", "script", "select", "sensor", "siren", "sun",
            "switch", "text", "time", "timer", "todo", "tts", "update", "vacuum", "valve", "water_heater", "weather",
            "zone");

    /** The internet's top-level domains, as the resource lists them: com, cz, xn--p1ai ... */
    static final Set<String> TOP_LEVEL_DOMAINS = topLevelDomains();

    private static Set<String> topLevelDomains() {
        try (var in = FactCapture.class.getResourceAsStream("/facts/top-level-domains.txt")) {
            if (in == null) throw new IllegalStateException("facts/top-level-domains.txt is not on the classpath");
            var out = new HashSet<String>();
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
                String l = line.strip();
                if (!l.isEmpty() && !l.startsWith("#")) out.add(l.toLowerCase(Locale.ROOT));
            }
            if (out.size() < 1000) throw new IllegalStateException("facts/top-level-domains.txt lists only " + out.size());
            return Set.copyOf(out);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("facts/top-level-domains.txt could not be read", e);
        }
    }

    /**
     * The places outside the owner's own network that {@code fact} names and that neither he stated
     * in the turn nor a kept fact names too, as whole words: a URL's host, a mail address, a public
     * IPv4 or IPv6 address, a name under an internet domain -- "nas.attacker.example.com",
     * "Forecast.Solar", and a file such as skill.py too, for .py is Paraguay's. An address of his
     * own network -- 10/8, 172.16/12, 192.168/16, a loopback, a link, a test net, fc00::/7 -- and a
     * name under no internet domain -- nas, homeassistant.local, sensors.yaml -- is no such place.
     * <p>
     * What he stated is his sentences but his questions ({@link #statements}), and not the URLs in
     * them: "summarise https://news.example.com/today" names a page to read, and a note that the
     * NAS backs up to news.example.com is the page's.
     */
    static List<String> unknownPlaces(String fact, List<String> said, List<AgentMemory.MemoryEntry> kept) {
        var places = new ArrayList<String>();
        String rest = fact;
        Matcher url = URL_HOST.matcher(fact);
        while (url.find()) {
            String host = url.group(1).replaceAll("^\\[|\\]$", "");
            if (outside(host)) places.add(host);
        }
        rest = URL_HOST.matcher(rest).replaceAll(" ");
        Matcher mail = MAIL.matcher(rest);
        while (mail.find()) places.add(mail.group());
        rest = MAIL.matcher(rest).replaceAll(" ");
        Matcher v4 = IPV4.matcher(rest);
        while (v4.find()) if (outside(v4.group())) places.add(v4.group());
        Matcher v6 = IPV6.matcher(rest);
        while (v6.find()) if (outside(v6.group())) places.add(v6.group());
        Matcher name = NAME.matcher(rest);
        while (name.find()) if (outside(name.group())) places.add(name.group());
        if (places.isEmpty()) return places;
        var his = new StringBuilder(URL_HOST.matcher(String.join("\n", statements(said))).replaceAll(" "));
        for (var e : kept) his.append('\n').append(e.content());
        String known = his.toString();
        places.removeIf(place -> among(place, known));
        return places;
    }

    /** Whether a host -- an address or a name -- is outside the owner's own network. */
    static boolean outside(String host) {
        String h = host.toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
        Matcher v4 = IPV4.matcher(h);
        if (v4.matches()) {
            int[] b = new int[4];
            for (int i = 0; i < 4; i++) {
                b[i] = Integer.parseInt(v4.group(i + 1));
                if (b[i] > 255) return false;   // no address
            }
            return !(b[0] == 10 || b[0] == 127 || b[0] == 0 || b[0] >= 224
                    || b[0] == 172 && b[1] >= 16 && b[1] <= 31
                    || b[0] == 192 && b[1] == 168
                    || b[0] == 169 && b[1] == 254
                    || b[0] == 100 && b[1] >= 64 && b[1] <= 127
                    || b[0] == 198 && (b[1] == 18 || b[1] == 19)
                    || b[0] == 192 && b[1] == 0 && b[2] == 2
                    || b[0] == 198 && b[1] == 51 && b[2] == 100
                    || b[0] == 203 && b[1] == 0 && b[2] == 113);
        }
        if (h.indexOf(':') >= 0) {
            if (!ipv6(h)) return false;   // a MAC, a time: no address
            String first = h.startsWith("::") ? "0" : h.substring(0, h.indexOf(':'));
            int g = Integer.parseInt(first.isEmpty() ? "0" : first, 16);
            if (g == 0) return false;                                  // ::1, ::, ::ffff:a.b.c.d
            if ((g & 0xfe00) == 0xfc00 || (g & 0xffc0) == 0xfe80 || (g & 0xff00) == 0xff00) return false;
            if (h.startsWith("2001:db8:") || h.equals("2001:db8::") || h.startsWith("2001:0db8:")) return false;
            return true;
        }
        int dot = h.lastIndexOf('.');
        if (dot < 0) return false;   // nas, localhost: a name of his network
        // A Home Assistant entity -- switch.garden, light.kitchen -- is its domain and its name, and
        // .garden and .kitchen are internet domains too.
        if (h.indexOf('.') == dot && ENTITY_DOMAINS.contains(h.substring(0, dot))) return false;
        String last = h.substring(dot + 1);
        if (!last.codePoints().allMatch(Character::isLetter) && !last.startsWith("xn--")) return false;
        if (TOP_LEVEL_DOMAINS.contains(last)) return true;
        try {
            return TOP_LEVEL_DOMAINS.contains(java.net.IDN.toASCII(last).toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException notADomain) {
            return false;
        }
    }

    /** Whether {@code s} is an IPv6 address: eight groups of one to four hex digits, or fewer around one "::". */
    static boolean ipv6(String s) {
        int gap = s.indexOf("::");
        if (gap >= 0 && s.indexOf("::", gap + 1) >= 0) return false;
        String[] sides = gap >= 0 ? new String[]{s.substring(0, gap), s.substring(gap + 2)} : new String[]{s};
        int groups = 0;
        for (String side : sides) {
            if (side.isEmpty()) continue;
            for (String group : side.split(":", -1)) {
                if (!group.matches("(?i)[0-9a-f]{1,4}")) return false;
                groups++;
            }
        }
        return gap >= 0 ? groups <= 7 : groups == 8;
    }

    // ── What is never kept ──

    /**
     * A line break of any kind, Markdown that makes a block -- a heading, a list, a quote, a fence --
     * or a link written so: "[your OwnClaw](https://evil.example/login)" is a link in every chat row
     * and Telegram message the fact is shown in, to wherever it says.
     */
    private static final Pattern STRUCTURE = Pattern.compile(
            "[\\n\\r\\u000B\\u000C\\u0085\\u2028\\u2029]|^\\s*(?:#|>|[-*+](?:\\s|$)|\\d+[.)](?:\\s|$))|```|~~~"
                    + "|\\[[^\\]]*\\]\\s*[(\\[]|!\\[|<(?:https?|ftp|mailto):");

    /**
     * Whether a note is more than one line of plain text: one with a line break or a Markdown block
     * would make sections of its own in every prompt it is put in -- "## Owner's standing
     * instructions" -- and one with a Markdown link a link the owner did not write. Refused whole,
     * never cut.
     */
    static boolean structured(String fact) {
        return STRUCTURE.matcher(fact).find();
    }

    /** What a fact, or its key, holds of what is never kept. */
    enum Verdict {
        /** Nothing. */
        NONE,
        /**
         * Only a word shaped like a credential -- eight characters or more mixing letters and digits:
         * rpi4-kitchen and RT-AX88U are, and so is sunflower77. Offered, the owner says which.
         */
        SHAPE,
        /** A secret, or a personal number: never kept, nor offered. */
        REFUSE
    }

    static Verdict worse(Verdict a, Verdict b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    /** A word that says a secret follows, in English, Czech and a few more, as a word of its own. */
    private static final Pattern SECRET_WORD = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])(?:"
            + "passwords?|pass\\s+words?|passwd|pwd|pw|pass(?:es)?|pass\\s?phrases?|pass\\s?codes?|passwort\\p{L}*"
            + "|contrase[ñn]as?|claves?|mots?\\s+de\\s+passe|парол\\p{L}*|pins?|puks?|psks?|cvv|cvc|otp|totp|hotp|codes?|tokens?|secrets?|keys?"
            + "|api\\s?keys?|credentials?|logins?|seeds?|mnemonics?|(?:recovery|backup|seed)\\s+phrases?"
            + "|combinations?|unlock\\p{L}*|disarm\\p{L}*"
            + "|hesl\\p{L}*|k[oó]d\\p{L}*|kl[ií][čc]\\p{L}*|p[řr]ihl[aá][šs]\\p{L}*|tajn\\p{L}*|kombinac\\p{L}*"
            + "|odem(?:yk|k|č)\\p{L}*|p[řr][íi]stupov\\p{L}*\\s+[úu]daj\\p{L}*"
            + ")(?![\\p{L}\\p{N}])");

    /** A Czech birth number, or a bank account with its bank's code after a slash. */
    private static final Pattern PERSONAL_NUMBER = Pattern.compile(
            "(?<![\\d/-])\\d{2}[0-35-8]\\d{3}\\s?/\\s?\\d{3,4}(?![\\d/])"
                    + "|(?<![\\d/-])(?:\\d{1,6}-)?\\d{6,10}\\s?/\\s?\\d{4}(?![\\d/])");

    /**
     * A Czech bank account without the slash -- its prefix and number, or its number and its bank's
     * code after a space -- which is one only when it passes the account's own check (mod 11): an
     * order number "1-123456" does not.
     */
    private static final Pattern ACCOUNT = Pattern.compile(
            "(?<![\\d-])(\\d{1,6})-(\\d{2,10})(?![\\d-])|(?<![\\d/-])(\\d{6,10})\\s\\d{4}(?![\\d/])");

    /** A word that says an account or a birth number follows: with six digits or more anywhere in the fact, one. */
    private static final Pattern NUMBER_WORD = Pattern.compile(
            "(?iu)(?<![\\p{L}])(?:accounts?|acct|iban|účet|účtu|účtem|účty|konto|birth\\s+number|rodné\\s+číslo|r\\.?\\s?č\\.)"
                    + "(?![\\p{L}])");
    private static final Pattern SIX_DIGITS = Pattern.compile("\\d(?:[ ./-]?\\d){5,}");

    /** Thirteen to nineteen digits, spaced, dotted or dashed or not: a payment card when they pass its check. */
    private static final Pattern CARD = Pattern.compile("(?<!\\d)(?:\\d[ .-]?){12,18}\\d(?!\\d)");

    /** Where an IBAN may begin: its country, then its check digits, in any case. */
    private static final Pattern IBAN_START = Pattern.compile("(?i)(?<![a-z0-9])[a-z]{2}\\d{2}");

    /**
     * An address, which is no credential however it mixes letters and digits: a MAC, colons, dashes
     * or dots between its parts; an IPv4 or IPv6; a date and time.
     */
    private static final Pattern ADDRESS = Pattern.compile("(?i)[0-9a-f]{2}(?:[:-][0-9a-f]{2}){5}"
            + "|[0-9a-f]{4}\\.[0-9a-f]{4}\\.[0-9a-f]{4}"
            + "|\\d{1,3}(?:\\.\\d{1,3}){3}(?:/\\d{1,2})?(?::\\d{1,5})?"
            + "|(?=[0-9a-f:]*:[0-9a-f:]*:)[0-9a-f:]+(?:/\\d{1,3})?"
            + "|\\d{4}-\\d{2}-\\d{2}(?:[t ]\\d{2}:\\d{2}(?::\\d{2})?(?:\\.\\d+)?(?:z|[+-]\\d{2}:?\\d{2})?)?");

    /** Base32, as a TOTP seed is written: sixteen or more of its characters, in groups of four or not. */
    private static final Pattern BASE32_GROUPS = Pattern.compile("(?<![A-Za-z0-9])(?:[A-Z2-7]{4} ?){4,}(?![A-Za-z0-9])");

    /** From this length a vault value is found inside other text, cased or spaced as it may be -- as the privacy filter finds it. */
    private static final int LOOSE_VAULT_VALUE = 8;

    /**
     * What {@code text} holds of what is never kept. Refused: a word saying a secret follows --
     * password, pass, pw, PIN, PUK, PSK, code, token, key, seed, combination, unlock, heslo, kód,
     * klíč, přístupové údaje, Passwort, пароль ... --, a run of base32 as a TOTP seed is, key
     * material the privacy filter finds, one of the vault's secrets, or a personal number -- a birth
     * number, a bank account, an IBAN, a payment card. Refused, never cut out: what is left of such
     * a fact is not what was said. Offered, the owner to say which: a word only shaped like a
     * credential -- eight characters or more mixing letters and digits, an address or a time aside
     * -- which is as often a host's name, rpi4-kitchen, ilo-dl380g9, as a password.
     * <p>
     * A vault value of eight characters or more is found however it is cased or spaced; a shorter
     * one only as a word of its own, as the privacy filter finds it: with a PIN of 1234 in the
     * vault, 192.168.1.234 and port 51234 are no secrets.
     */
    static Verdict secret(String text, Map<String, String> vault) {
        if (text == null || text.isBlank()) return Verdict.NONE;
        if (SECRET_WORD.matcher(text).find() || PERSONAL_NUMBER.matcher(text).find()) return Verdict.REFUSE;
        if (NUMBER_WORD.matcher(text).find() && SIX_DIGITS.matcher(text).find()) return Verdict.REFUSE;
        if (BASE32_GROUPS.matcher(text).find() || iban(text) || account(text)) return Verdict.REFUSE;
        Matcher card = CARD.matcher(text);
        while (card.find()) if (luhn(card.group().replaceAll("[ .-]", ""))) return Verdict.REFUSE;
        boolean shaped = false;
        for (String word : text.split("\\s+")) {
            String w = word.replaceAll("^[\\p{Punct}„“”‘’«»]+|[\\p{Punct}„“”‘’«»]+$", "");
            if (base32(w)) return Verdict.REFUSE;
            shaped |= credentialShaped(w);
        }
        if (!Redactor.cuttable(text, vault).text().equals(text)) return Verdict.REFUSE;
        String loose = loose(text);
        for (String value : vault.values()) {
            if (value == null) continue;
            if (value.length() >= LOOSE_VAULT_VALUE ? loose.contains(loose(value))
                    : Redactor.vaultValueAt(text, value, 0) >= 0) return Verdict.REFUSE;
        }
        return shaped ? Verdict.SHAPE : Verdict.NONE;
    }

    /**
     * What a key holds of what is never kept, by the same rules as a fact, its parts read as the
     * words they are: guest_wifi_sunflower77 is a key of an innocent fact with a password-shaped
     * word in it, and rpi4_kitchen a key with none.
     */
    static Verdict secretKey(String key, Map<String, String> vault) {
        return key == null || key.isBlank() ? Verdict.NONE : secret(key.replaceAll("[_\\-.\\s]+", " "), vault);
    }

    /** Eight characters or more mixing letters and digits, in any case -- but an address or a time. */
    private static boolean credentialShaped(String w) {
        return w.codePointCount(0, w.length()) >= 8 && w.codePoints().anyMatch(Character::isLetter)
                && w.codePoints().anyMatch(Character::isDigit) && !ADDRESS.matcher(w).matches();
    }

    /** Sixteen base32 characters or more, as one word, written in capitals or holding a digit of base32. */
    private static boolean base32(String w) {
        String plain = w.replaceAll("=+$", "");
        if (plain.length() < 16 || !plain.matches("[A-Za-z2-7]+")) return false;
        return plain.matches(".*[2-7].*") || plain.equals(plain.toUpperCase(Locale.ROOT));
    }

    /** Whether {@code text} holds a Czech account number without the slash that passes the account's check. */
    private static boolean account(String text) {
        Matcher m = ACCOUNT.matcher(text);
        while (m.find()) {
            if (m.group(1) != null ? czechAccount(m.group(1), 6) && czechAccount(m.group(2), 10)
                    : czechAccount(m.group(3), 10)) return true;
        }
        return false;
    }

    /** The Czech bank account check: the digits, weighted from the right 1, 2, 4, 8, 5, 10, 9, 7, 3, 6, sum to a multiple of 11. */
    private static boolean czechAccount(String digits, int width) {
        if (digits.length() > width || digits.chars().allMatch(c -> c == '0')) return false;
        int[] weights = {1, 2, 4, 8, 5, 10, 9, 7, 3, 6};
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            sum += (digits.charAt(digits.length() - 1 - i) - '0') * weights[i];
        }
        return sum % 11 == 0;
    }

    /**
     * Whether {@code text} holds an IBAN that passes its check, in any case: written whole, or in
     * groups of four after its first four, as it is printed -- not across the words after it.
     */
    static boolean iban(String text) {
        Matcher m = IBAN_START.matcher(text);
        while (m.find()) {
            var sb = new StringBuilder(m.group().toUpperCase(Locale.ROOT));
            int i = m.end();
            if (i < text.length() && text.charAt(i) == ' ') {
                // Grouped: a space, then four letters or digits -- the last group may be shorter.
                while (i < text.length() && text.charAt(i) == ' ' && sb.length() < 34) {   // 34: the longest IBAN (ISO 13616)
                    int j = i + 1;
                    while (j < text.length() && j - i - 1 < 4 && isAlnum(text.charAt(j))) j++;
                    int group = j - i - 1;
                    if (group == 0 || j < text.length() && isAlnum(text.charAt(j))) break;   // not a group of its own
                    sb.append(text, i + 1, j);
                    if (sb.length() >= 15 && mod97(upper(sb)) == 1) return true;
                    if (group < 4) break;
                    i = j;
                }
            } else {
                for (; i < text.length() && isAlnum(text.charAt(i)) && sb.length() < 34; i++) {
                    sb.append(text.charAt(i));
                    if (sb.length() >= 15 && mod97(upper(sb)) == 1) return true;
                }
            }
        }
        return false;
    }

    private static boolean isAlnum(char ch) {
        return ch < 128 && Character.isLetterOrDigit(ch);
    }

    private static String upper(CharSequence s) {
        return s.toString().toUpperCase(Locale.ROOT);
    }

    private static int mod97(CharSequence iban) {
        String moved = iban.subSequence(4, iban.length()).toString() + iban.subSequence(0, 4);
        int r = 0;
        for (int i = 0; i < moved.length(); i++) {
            char ch = moved.charAt(i);
            int v = Character.isDigit(ch) ? ch - '0' : ch - 'A' + 10;
            r = v > 9 ? (r * 100 + v) % 97 : (r * 10 + v) % 97;
        }
        return r;
    }

    /** Letters and digits only, lowercased: a vault value is found however it is cased or spaced. */
    private static String loose(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static boolean luhn(String digits) {
        int sum = 0;
        boolean twice = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (twice) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            twice = !twice;
        }
        return sum % 10 == 0;
    }
}
