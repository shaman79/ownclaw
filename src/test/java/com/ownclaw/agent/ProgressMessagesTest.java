package com.ownclaw.agent;

import com.ownclaw.agent.tools.Tool;
import com.ownclaw.llm.LlmException;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.LlmResponse;
import com.ownclaw.llm.Replies;
import com.ownclaw.llm.ToolCall;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import com.ownclaw.privacy.PrivateIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.ownclaw.agent.AssistantPartsTest.tool;
import static com.ownclaw.agent.LoopRig.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What the owner sees while an attended task works: a message in its chat before each step --
 * who acts, which step, which tool, how long, what it has cost, and what the model wrote beside
 * the call -- one for each tool call of a delegation, with the local model's words and the call
 * for him alone, and the local model's summary of each private result of the cloud's calls, which
 * only he may read. Saved in the task's own chat and shown live; never in a prompt; and nothing
 * of a task once it has ended.
 */
class ProgressMessagesTest {

    static final String CLOUD = "\u2601\uFE0F";   // ☁️
    static final String LOCAL = "\uD83C\uDFE0";    // 🏠

    /** A header line as a pattern: the actor's emoji, what the row is about, the tool, time and cost. */
    static String head(String actor, String what, String tool) {
        return java.util.regex.Pattern.quote(actor + " " + what + " · " + tool + " · ") + "[0-9.]+m?s · \\$\\d+\\.\\d\\d";
    }

    /** The model calls a tool once the row starting {@code head} is in the chat: posted while the task runs. */
    static Reply once(LoopRig rig, String head, Reply then) {
        return c -> {
            awaitRow(rig, head);
            return then.answer(c);
        };
    }

    /** The JSON a row's metadata holds, read. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> metadata(Map<String, Object> row) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(String.valueOf(row.get("metadata")), Map.class);
    }

    static final String STATEMENT = "Closing balance 48,213.07 CZK on account CZ65 0800 0000 1920 0014 5399, "
            + "statement of 30 September";
    static final String SUMMARY = "Zůstatek k 30. září je 48 213,07 Kč.";

    static final Tool PING = tool("ping", List.of(), p -> "pong");
    /**
     * A skill that reads a personal source -- the statement from the mailbox it arrives in, with
     * an IMAP credential: what it returns is PRIVATE.
     */
    static final Tool BANK = tool("bank_fetch", List.of("IMAP_PASS"), p -> STATEMENT);

    /** A local model answering every call with {@code answer}, keeping what it was asked. */
    static final class Local implements LlmProvider {
        final List<List<LlmMessage>> asked = new CopyOnWriteArrayList<>();
        final List<LlmRequestConfig> configs = new CopyOnWriteArrayList<>();
        final Reply answer;
        Local(Reply answer) { this.answer = answer; }
        public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
            asked.add(List.copyOf(m));
            configs.add(c);
            try {
                return answer.answer(c);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        public boolean isAvailable() { return true; }
        public String name() { return "ollama"; }
    }

    static List<Map<String, Object>> progress(LoopRig rig) {
        return rig.jdbc.queryForList("SELECT content, private_content, session_id, metadata FROM conversations "
                + "WHERE role = 'progress' ORDER BY rowid");
    }

    /** The progress row whose content starts with {@code head}, once it is there. */
    static Map<String, Object> awaitRow(LoopRig rig, String head) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < until) {
            for (var row : progress(rig)) {
                if (String.valueOf(row.get("content")).startsWith(head)) return row;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no progress row starting " + head + " in " + progress(rig));
    }

    /** Not one canary window of {@code secret} is in {@code text}. */
    static void holdsNoWindowOf(String text, String secret) {
        String said = PrivateIndex.normalise(text);
        String s = PrivateIndex.normalise(secret);
        for (int i = 0; i + PrivateIndex.WINDOW <= s.length(); i++) {
            assertFalse(said.contains(s.substring(i, i + PrivateIndex.WINDOW)), "a window at " + i + ": " + text);
        }
    }

    @Test
    @DisplayName("before each step, one message in the task's own chat: the step, the tool, time and cost, and the model's words")
    void oneMessagePerStep(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(PING));
        rig.cloud.think.add(c -> Replies.of("The first router answered; now the second.", 1_000, 100, 0, 0,
                "tool_use", List.of(new ToolCall("a", "ping", Map.of("host", "192.0.2.2")))));
        rig.cloud.think.add(call(AgentAction.SKILL_CREATE, Map.of("name", "router_check",
                "description", "Check a router.", "parameters", "{}")));
        rig.cloud.codegen.add(SkillCodegenTest.finished(SkillCodegenTest.module("")));
        rig.cloud.think.add(respond("Both answer."));
        String session = rig.chat.createSession("u1", "Routers");
        rig.chat.createSession("u1", "Opened while it ran");
        var seen = rig.statuses();

        AgentResult r = rig.turn(session, "check the routers");

        assertEquals("Both answer.", r.response(), "the answer is unchanged");
        var rows = progress(rig);
        assertEquals(2, rows.size(), "one per step that ran, none for the answer: " + rows);
        assertTrue(String.valueOf(rows.get(0).get("content")).matches(head(CLOUD, "Step 1", "ping")
                + "\n\nThe first router answered; now the second\\."), String.valueOf(rows.get(0).get("content")));
        assertTrue(String.valueOf(rows.get(1).get("content")).matches(head(CLOUD, "Step 2", "skill_create router_check")),
                "no words beside the call, the header alone: " + rows.get(1).get("content"));
        for (var row : rows) {
            assertEquals(session, row.get("session_id"), "in the chat the task's message was saved in");
            assertEquals(r.taskId(), metadata(row).get("taskId"));
            assertNull(row.get("private_content"));
        }
        // The header as data, for the page to draw: who acts, the step, the tool, time and cost.
        @SuppressWarnings("unchecked")
        var header = (Map<String, Object>) metadata(rows.get(1)).get("progress");
        assertEquals("cloud", header.get("actor"));
        assertEquals(2, header.get("step"));
        assertEquals("skill_create", header.get("tool"));
        assertEquals("router_check", header.get("skill"));
        assertTrue(((Number) header.get("elapsedMs")).longValue() >= 0 && header.get("costUsd") instanceof Number, header.toString());
        var live = seen.stream().filter(m -> m.type() == StatusMessage.Type.PROGRESS_MESSAGE).toList();
        assertEquals(2, live.size());
        for (var m : live) {
            assertEquals(r.taskId(), m.taskId());
            assertEquals(session, m.data().get("sessionId"));
            assertNull(m.data().get("telegram"), "a web chat task's progress is not sent to Telegram");
        }
        assertEquals(rows.get(0).get("content"), live.get(0).text());
        assertEquals(header.toString(), String.valueOf(live.get(1).data().get("progress")),
                "the live frame carries the header the row keeps");
        var reload = rig.chat.getSessionMessages("u1", session, null, null).messages().stream()
                .filter(m -> "progress".equals(m.get("role"))).toList();
        var reloaded = (com.fasterxml.jackson.databind.JsonNode) reload.get(1).get("progress");
        assertEquals("cloud", reloaded.path("actor").asText(), "and a reload reads it back: " + reload);
        assertEquals("router_check", reloaded.path("skill").asText());
        // Mutation: post after the step -> the header's step runs ahead; post for respond -> 3 rows.
    }

    @Test
    @DisplayName("no progress for a scheduled or background run, or a task with no chat")
    void noneWithoutAWaitingChat(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(PING));
        String session = rig.chat.createSession("u1", "Background");
        for (int i = 0; i < 3; i++) {
            rig.cloud.think.add(call("ping", Map.of("n", i)));
            rig.cloud.think.add(respond("done"));
        }
        String row = rig.chat.saveMessage("u1", session, "user", "/bg ping it");

        rig.loop.executeFull("u1", "ping it", true, row, List.of(), TaskChat.Channel.WEB);
        rig.loop.executeFull("u1", "ping it", false, null, List.of(), TaskChat.Channel.WEB);
        rig.loop.executeFull("u1", "ping it", false, row, List.of(), null);

        assertEquals(List.of(), progress(rig));
    }

    @Test
    @DisplayName("the next task in the chat never reads a progress row")
    void noPromptReadsThem(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(PING));
        String session = rig.chat.createSession("u1", "Routers");
        rig.cloud.think.add(c -> Replies.of("PROGRESS-NARRATION", 1_000, 100, 0, 0, "tool_use",
                List.of(new ToolCall("a", "ping", Map.of()))));
        rig.cloud.think.add(respond("It answers."));
        rig.turn(session, "ping the router");
        assertEquals(1, progress(rig).size());

        String next = rig.chat.saveMessage("u1", session, "user", "and now?");
        var context = rig.chat.contextOf("u1", next);
        assertEquals(List.of("user", "assistant"), context.stream().map(m -> m.get("role")).toList());

        rig.cloud.think.add(respond("Still."));
        rig.turn(session, "and now?");
        String prompt = String.join("\n", userParts(rig.cloud.calls("think").get(2)));
        assertFalse(prompt.contains("PROGRESS-NARRATION") || prompt.contains("Step 1 ·") || prompt.contains(CLOUD), prompt);
        assertTrue(prompt.contains("ASSISTANT: It answers."), prompt);
    }

    @Test
    @DisplayName("a private result is summarised by the local model for the owner alone: the row's content holds none of it")
    void aPrivateResultIsSummarisedForTheOwner(@TempDir Path tmp) throws Exception {
        var local = new Local(c -> Replies.of(SUMMARY, 400, 30));
        var rig = new LoopRig(tmp, List.of(BANK), 600, local);
        String session = rig.chat.createSession("u1", "Bank");
        rig.cloud.think.add(call("bank_fetch", Map.of()));
        rig.cloud.think.add(once(rig, LOCAL + " Result 1 · bank_fetch", respond("The statement is in.")));
        var seen = rig.statuses();

        AgentResult r = rig.turn(session, "Jaký mám zůstatek?");
        var row = awaitRow(rig, LOCAL + " Result 1 · bank_fetch");

        String content = String.valueOf(row.get("content"));
        assertTrue(content.matches(head(LOCAL, "Result 1", "bank_fetch") + "\n\nA private summary, shown only to you\\."),
                content);
        holdsNoWindowOf(content, STATEMENT);
        holdsNoWindowOf(content, SUMMARY);
        String line = content.substring(0, content.indexOf('\n'));
        assertEquals(line + "\n\n" + SUMMARY, row.get("private_content"), "the chip says who wrote it; no prose about it");
        assertEquals("local", ((Map<?, ?>) metadata(row).get("progress")).get("actor"));
        assertEquals(session, row.get("session_id"));
        String asked = local.asked.get(0).get(1).content();
        assertTrue(asked.contains("Jaký mám zůstatek?") && asked.contains(STATEMENT),
                "the owner's message, for its language, and the result whole: " + asked);
        assertTrue(local.configs.get(0).withoutThinking(),
                "a summary is written straight away, not after minutes of reasoning at 8 tokens a second");
        var live = seen.stream().filter(m -> m.type() == StatusMessage.Type.PROGRESS_MESSAGE
                && m.text().startsWith(LOCAL + " Result 1")).findFirst().orElseThrow();
        assertEquals(content, live.text(), "what is stored and forwarded is the note");
        assertEquals(row.get("private_content"), live.data().get("ownerText"), "the owner's screens get the summary");
        assertTrue(r.taskId() != null && String.valueOf(row.get("metadata")).contains(r.taskId()));

        rig.cloud.think.add(respond("Done."));
        rig.turn(session, "díky");
        for (var call : rig.cloud.calls) {
            for (var m : call.messages()) holdsNoWindowOf(m.content(), SUMMARY);
        }
        // Mutation: save the summary as the content -> the window check fails; put it in the
        // context -> the next turn's prompt holds it.
    }

    @Test
    @DisplayName("a summary that cannot be made says so plainly: the local model is down, or failed")
    void aFailedSummaryIsSaid(@TempDir Path tmp) throws Exception {
        var down = new LoopRig(tmp.resolve("down"), List.of(BANK));
        down.cloud.think.add(call("bank_fetch", Map.of()));
        down.cloud.think.add(once(down, LOCAL + " Result 1", respond("ok")));
        down.turn(down.chat.createSession("u1", "Bank"), "fetch the statement");
        assertTrue(String.valueOf(awaitRow(down, LOCAL + " Result 1").get("content")).matches(
                head(LOCAL, "Result 1", "bank_fetch") + "\n\nNot summarised: the local model is not answering\\."));

        var failing = new LoopRig(tmp.resolve("failing"), List.of(BANK), 600,
                new Local(c -> { throw new LlmException("ollama", "HTTP 500: " + STATEMENT); }));
        failing.cloud.think.add(call("bank_fetch", Map.of()));
        failing.cloud.think.add(once(failing, LOCAL + " Result 1", respond("ok")));
        failing.turn(failing.chat.createSession("u1", "Bank"), "fetch the statement");
        var row = awaitRow(failing, LOCAL + " Result 1");
        assertTrue(String.valueOf(row.get("content")).matches(head(LOCAL, "Result 1", "bank_fetch")
                + "\n\nNot summarised: it failed \\(LlmException\\)\\."), "its message can quote the result, so only its type");
        assertNull(row.get("private_content"));
    }

    @Test
    @DisplayName("a slow summary never holds up a step, and ends with its task: nothing is posted after the answer")
    void aSummaryEndsWithItsTask(@TempDir Path tmp) throws Exception {
        var begun = new CountDownLatch(1);
        var ended = new CountDownLatch(1);
        var local = new Local(c -> {
            c.progress().calling(ended::countDown);   // the call's cancel, as a provider hands it over
            begun.countDown();
            assertTrue(ended.await(10, TimeUnit.SECONDS), "nothing ended the summary");
            c.progress().onProgress();                 // asked once more, as a provider does
            return Replies.of(SUMMARY, 400, 30);
        });
        var rig = new LoopRig(tmp, List.of(BANK, PING), 600, local);
        rig.cloud.think.add(call("bank_fetch", Map.of("month", 8)));
        rig.cloud.think.add(call("bank_fetch", Map.of("month", 9)));
        rig.cloud.think.add(c -> {
            assertTrue(begun.await(5, TimeUnit.SECONDS), "the first summary never began");
            return call("ping", Map.of()).answer(c);
        });
        rig.cloud.think.add(respond("Fetched and pinged."));
        var seen = rig.statuses();

        AgentResult r = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> rig.turn(rig.chat.createSession("u1", "Bank"), "fetch and ping"));

        assertEquals("Fetched and pinged.", r.response(), "the task finished while its summary was being written");
        assertTrue(ended.await(2, TimeUnit.SECONDS), "the task's end did not end the summary under way");
        Thread.sleep(300);
        assertEquals(1, local.asked.size(), "the second summary, not yet begun, is never asked for: " + local.asked.size());
        assertEquals(3, progress(rig).size(), "the three steps, and nothing after them: " + progress(rig));
        assertTrue(seen.stream().noneMatch(m -> m.type() == StatusMessage.Type.PROGRESS_MESSAGE
                && m.text().startsWith(LOCAL)), "nor shown");
        // Mutation: let close() leave the summaries be -> the second one is asked for, and both
        // land below the answer, among the next task's rows.
    }

    @Test
    @DisplayName("a tool name the local model made up is refused, and never shown: not in the chat, not to the cloud")
    void aMadeUpToolNameIsNeverShown(@TempDir Path tmp) throws Exception {
        // Typed after it read the statement, the name can carry what it read: here the account.
        String iban = "CZ6508000000192000145399";
        var script = new java.util.ArrayDeque<>(List.of(DelegationBehaviourTest.call(iban, Map.of()),
                DelegationBehaviourTest.done("The balance is in the statement.")));
        var told = new CopyOnWriteArrayList<String>();
        var local = new LlmProvider() {
            public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
                if (m.get(0).content().startsWith("You summarise")) return Replies.of("a summary", 1, 1);
                told.add(m.get(m.size() - 1).content());
                return Replies.of(script.isEmpty() ? DelegationBehaviourTest.done("done") : script.poll(), 1, 1);
            }
            public boolean isAvailable() { return true; }
            public String name() { return "ollama"; }
        };
        var rig = new LoopRig(tmp, List.of(BANK), 600, local);
        rig.cloud.think.add(call("bank_fetch", Map.of()));
        rig.cloud.think.add(call(AgentAction.DELEGATE, Map.of("goal", "Read {{1}} and give me the balance.")));
        rig.cloud.think.add(respond("Done."));
        String session = rig.chat.createSession("u1", "Bank");

        rig.turn(session, "what is my balance?");

        assertTrue(told.get(1).contains("Not run: there is no tool named"), told.toString());
        for (var row : progress(rig)) assertFalse(String.valueOf(row.get("content")).contains(iban), row.toString());
        for (var call : rig.cloud.calls) {
            for (var m : call.messages()) assertFalse(m.content().contains(iban), m.content());
        }
        assertEquals(List.of(), rig.jdbc.queryForList("SELECT tool_name FROM skill_usage WHERE tool_name = ?", iban));
        // Mutation: record the call under the name the model wrote -> "Result 2 (CZ65...)" in
        // the chat, and in the cloud's next prompt.
    }

    @Test
    @DisplayName("a stopped task stops its summaries: the one being written ends, and posts nothing")
    void aStopEndsTheSummary(@TempDir Path tmp) throws Exception {
        var calling = new CountDownLatch(1);
        var ended = new CountDownLatch(1);
        var local = new Local(c -> {
            c.progress().calling(ended::countDown);   // the call's cancel, as a provider hands it over
            calling.countDown();
            assertTrue(ended.await(10, TimeUnit.SECONDS), "the stop never ended the call");
            c.progress().onProgress();                  // asked once more, as a provider does
            return Replies.of(SUMMARY, 400, 30);
        });
        var rig = new LoopRig(tmp, List.of(BANK, tool("slow_scan", List.of(), p -> {
            try {
                Thread.sleep(1_500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "scanned";
        })), 600, local);
        rig.cloud.think.add(call("bank_fetch", Map.of()));
        rig.cloud.think.add(call("slow_scan", Map.of()));
        var stop = new Thread(() -> {
            try {
                if (calling.await(10, TimeUnit.SECONDS)) Thread.sleep(300);
            } catch (InterruptedException e) {
                return;
            }
            rig.cancellation.requestAll("u1", "you pressed Stop");
        });
        stop.start();

        AgentResult r = rig.turn(rig.chat.createSession("u1", "Bank"), "fetch and scan");
        stop.join();

        assertEquals(AgentResult.TerminationReason.CANCELLED, r.terminationReason(), r.response());
        assertTrue(ended.await(2, TimeUnit.SECONDS), "the stop did not end the summary");
        Thread.sleep(300);
        assertTrue(progress(rig).stream().noneMatch(row -> String.valueOf(row.get("content")).startsWith(LOCAL)),
                "the owner stopped it: no row says so again " + progress(rig));
    }

    @Test
    @DisplayName("each tool call of a delegation is announced in the chat as the local model's")
    void aDelegationsTurnsAreShown(@TempDir Path tmp) throws Exception {
        var local = new DelegationBehaviourTest.Scripted(DelegationBehaviourTest.call("ping", Map.of()),
                DelegationBehaviourTest.call("ping", Map.of("n", 2)), DelegationBehaviourTest.done("pinged twice"));
        var rig = new LoopRig(tmp, List.of(PING), 600, local);
        rig.cloud.think.add(call(AgentAction.DELEGATE, Map.of("goal", "Ping the router twice.", "tools", "ping")));
        rig.cloud.think.add(respond("It answered twice."));

        AgentResult r = rig.turn(rig.chat.createSession("u1", "Router"), "ping the router twice");

        var rows = progress(rig);
        var contents = rows.stream().map(row -> String.valueOf(row.get("content"))).toList();
        assertEquals(3, contents.size(), contents.toString());
        assertTrue(contents.get(0).matches(head(CLOUD, "Step 1", "delegate")), contents.get(0));
        assertTrue(contents.get(1).matches(head(LOCAL, "Turn 1", "ping")), "a chip, not a label: " + contents.get(1));
        assertTrue(contents.get(2).matches(head(LOCAL, "Turn 2", "ping")), contents.get(2));
        assertNull(rows.get(1).get("private_content"), "a call with no arguments has nothing more to show");
        assertEquals(contents.get(2) + "\n\n- n: `2`", rows.get(2).get("private_content"), "the call, for the owner");
        @SuppressWarnings("unchecked")
        var header = (Map<String, Object>) metadata(rows.get(2)).get("progress");
        assertEquals("local", header.get("actor"));
        assertEquals(2, header.get("turn"));
        assertEquals("ping", header.get("tool"));
        // The cloud's instructions to the local model are not in the chat; the task page has them.
        assertTrue(contents.stream().noneMatch(c -> c.contains("Ping the router twice")), contents.toString());
        var steps = (List<?>) new com.ownclaw.observability.TaskTraceService(rig.events).trace("u1", r.taskId())
                .orElseThrow().get("steps");
        assertEquals("Ping the router twice.", ((Map<?, ?>) steps.get(0)).get("goal"), steps.toString());
    }

    /** A shell skill, as the owner's router work runs it. */
    static final Tool SHELL = tool("shell_exec", List.of(), p -> "default via 192.0.2.1 dev wan");

    /** A local model on the native protocol: each turn's words beside its call, then done. */
    static final class Native implements LlmProvider {
        final List<List<LlmMessage>> asked = new CopyOnWriteArrayList<>();
        final java.util.Deque<LlmResponse> turns = new java.util.concurrent.ConcurrentLinkedDeque<>();
        Native(LlmResponse... turns) { this.turns.addAll(List.of(turns)); }
        public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
            asked.add(List.copyOf(m));
            if (m.get(0).content().startsWith("You summarise")) return Replies.of("a summary", 1, 1);
            LlmResponse next = turns.poll();
            return next != null ? next : turn("", "done", Map.of("summary", "done"));
        }
        public boolean isAvailable() { return true; }
        public boolean supportsTools() { return true; }
        public String name() { return "ollama"; }

        static LlmResponse turn(String words, String tool, Map<String, Object> args) {
            return Replies.of(words, 100, 20, 0, 0, "stop", List.of(new ToolCall("c-" + tool, tool, args)));
        }
    }

    @Test
    @DisplayName("a local turn shows the owner what the model says it is doing and the call it makes -- for him alone")
    void aLocalTurnSaysWhatItDoes(@TempDir Path tmp) throws Exception {
        String words = "Zjišťuji výchozí bránu routeru.";
        var local = new Native(Native.turn(words, "shell_exec", Map.of("command", "ip route show default")),
                Native.turn("", "done", Map.of("summary", "The default route goes via the WAN.")));
        var rig = new LoopRig(tmp, List.of(SHELL), 600, local);
        rig.cloud.think.add(call(AgentAction.DELEGATE, Map.of("goal", "Find the router's default route.",
                "tools", "shell_exec")));
        rig.cloud.think.add(respond("It goes via the WAN."));
        var seen = rig.statuses();

        rig.turn(rig.chat.createSession("u1", "Router"), "Kudy vede výchozí cesta?");

        var row = progress(rig).get(1);
        String content = String.valueOf(row.get("content"));
        assertTrue(content.matches(head(LOCAL, "Turn 1", "shell_exec")), "the header alone: " + content);
        assertEquals(content + "\n\n" + words + "\n\n```sh\nip route show default\n```", row.get("private_content"),
                "its words, then the command as a shell block");
        var live = seen.stream().filter(m -> m.type() == StatusMessage.Type.PROGRESS_MESSAGE
                && m.text().startsWith(LOCAL)).findFirst().orElseThrow();
        assertEquals(content, live.text(), "what is stored and forwarded is the header");
        assertEquals(row.get("private_content"), live.data().get("ownerText"), "the owner's screens get the rest");
        String prompt = local.asked.get(0).get(0).content();
        assertTrue(prompt.contains("with each tool call, write one short") && prompt.contains("Kudy vede výchozí cesta?"),
                "asked for the sentence, in the language of the owner's request: " + prompt);
        for (var call : rig.cloud.calls) {
            for (var m : call.messages()) {
                assertFalse(m.content().contains("výchozí bránu") || m.content().contains("ip route show"), m.content());
            }
        }
        // Mutation: put the words in the content -> the content check fails; leave out the
        // instruction -> the prompt check fails.
    }

    @Test
    @DisplayName("an unattended delegation is not asked to narrate: nobody reads it")
    void anUnattendedDelegationIsNotAskedToNarrate() {
        var local = new Native(Native.turn("", "done", Map.of("summary", "nothing to do")));
        var executor = DelegationBehaviourTest.executor(local, new DelegationBehaviourTest.Usage());
        executor.execute(DelegationBehaviourTest.plan("Fetch the menu."), DelegationBehaviourTest.task(),
                DelegationBehaviourTest.UNCOUNTED);
        String prompt = local.asked.get(0).get(0).content();
        assertFalse(prompt.contains("write one short") || prompt.contains("the morning menu"), prompt);
    }

    @Test
    @DisplayName("a delegation's own private results get no summary: the local model read them itself")
    void noSummaryOfADelegationsResults(@TempDir Path tmp) throws Exception {
        var local = new Native(Native.turn("Stahuji výpis.", "bank_fetch", Map.of()),
                Native.turn("", "done", Map.of("summary", "Fetched.")));
        var rig = new LoopRig(tmp, List.of(BANK), 600, local);
        rig.cloud.think.add(call(AgentAction.DELEGATE, Map.of("goal", "Fetch the statement.", "tools", "bank_fetch")));
        rig.cloud.think.add(c -> {
            Thread.sleep(300);   // a summary queued by the delegation would have been posted by now
            return respond("Fetched.").answer(c);
        });

        rig.turn(rig.chat.createSession("u1", "Bank"), "stáhni výpis");

        assertTrue(local.asked.stream().noneMatch(m -> m.get(0).content().startsWith("You summarise")),
                "the local model is never asked to summarise its own result");
        var contents = progress(rig).stream().map(row -> String.valueOf(row.get("content"))).toList();
        assertEquals(2, contents.size(), "the step and the turn, no result row: " + contents);
        assertTrue(contents.get(1).matches(head(LOCAL, "Turn 1", "bank_fetch")), contents.get(1));
        // Mutation: summarise every private result again -> a third row, "Result 1".
    }

    @Test
    @DisplayName("when the cloud is not available, the local model's words beside a step are the owner's alone: it may have read private data")
    void aLocalStepsWordsAreTheOwnersAlone(@TempDir Path tmp) throws Exception {
        String quoting = "The statement says: " + STATEMENT + ". Fetching September too.";
        var local = new Native(Native.turn("Stahuji výpis.", "bank_fetch", Map.of()),
                Native.turn(quoting, "bank_fetch", Map.of("month", 9)),
                Native.turn("", AgentAction.RESPOND, Map.of("message", "Hotovo.")));
        var rig = new LoopRig(tmp, List.of(BANK), 600, local);
        rig.cloud.available = false;        // the local model does the thinking
        var seen = rig.statuses();

        rig.turn(rig.chat.createSession("u1", "Bank"), "stáhni výpis");

        var row = awaitRow(rig, LOCAL + " Step 2 · bank_fetch");
        String content = String.valueOf(row.get("content"));
        assertTrue(content.matches(head(LOCAL, "Step 2", "bank_fetch")), "the header alone: " + content);
        for (var r : progress(rig)) holdsNoWindowOf(String.valueOf(r.get("content")), STATEMENT);
        assertEquals(content + "\n\n" + quoting, row.get("private_content"), "its words, for the owner");
        var live = seen.stream().filter(m -> m.type() == StatusMessage.Type.PROGRESS_MESSAGE
                && m.text().startsWith(LOCAL + " Step 2")).findFirst().orElseThrow();
        assertEquals(content, live.text(), "what is stored and forwarded is the header");
        assertEquals(row.get("private_content"), live.data().get("ownerText"), "the owner's screens get the rest");
        // Mutation: post the local model's words as the content, as the cloud's are -> the
        // statement is in the content column, which ops reads.
    }

    @Test
    @DisplayName("a goal the local model wrote, the cloud not being available, is not kept on the step: it may quote what it read")
    void aLocalGoalIsNotKept(@TempDir Path tmp) throws Exception {
        var local = new Native(Native.turn("", "bank_fetch", Map.of()),
                Native.turn("", AgentAction.DELEGATE, Map.of("goal", "Mail the owner: " + STATEMENT, "tools", "ping")),
                Native.turn("", "ping", Map.of()),
                Native.turn("", "done", Map.of("summary", "Mailed.")),
                Native.turn("", AgentAction.RESPOND, Map.of("message", "Mailed.")));
        var rig = new LoopRig(tmp, List.of(BANK, PING), 600, local);
        rig.cloud.available = false;        // the local model does the thinking, and writes the goal

        AgentResult r = rig.turn(rig.chat.createSession("u1", "Bank"), "mail me the statement");

        var details = rig.jdbc.queryForList("SELECT details FROM events WHERE event_type = 'step' "
                + "AND json_extract(details, '$.tool') = 'delegate'", String.class);
        assertEquals(1, details.size(), "the delegation ran: " + details);
        assertTrue(details.get(0).contains("\"success\":true"), details.get(0));
        assertFalse(details.get(0).contains("\"goal\""), "no goal the cloud did not write: " + details.get(0));
        holdsNoWindowOf(details.get(0), STATEMENT);
        var steps = (List<?>) new com.ownclaw.observability.TaskTraceService(rig.events).trace("u1", r.taskId())
                .orElseThrow().get("steps");
        assertNull(((Map<?, ?>) steps.get(1)).get("goal"), steps.toString());
        // Mutation: keep every delegation's goal, as the cloud's is kept -> the statement is in
        // the events row, which ops reads.
    }

    @Test
    @DisplayName("where a task's progress is shown follows where it came from: Telegram's to Telegram too, an ops turn's nowhere")
    void theChannelDecidesWhereItIsShown(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(PING));
        String session = rig.chat.createSession("u1", "Ops check");
        var seen = rig.statuses();
        for (var channel : List.of(TaskChat.Channel.TELEGRAM, TaskChat.Channel.OPS)) {
            rig.cloud.think.add(call("ping", Map.of()));
            rig.cloud.think.add(respond("up"));
            String row = rig.chat.saveMessage("u1", session, "user", "is it up?");
            rig.loop.executeFull("u1", "is it up?", false, row, List.of(), channel);
        }

        assertEquals(2, progress(rig).size(), "saved for both");
        var live = seen.stream().filter(m -> m.type() == StatusMessage.Type.PROGRESS_MESSAGE).toList();
        assertEquals(1, live.size(), "the ops turn's is saved, and shown nowhere");
        assertEquals(Boolean.TRUE, live.get(0).data().get("telegram"));
    }

    @Test
    @DisplayName("what the filter took from a result is said in counts, each part only when it is not zero")
    void theFilterNote() {
        var redactor = new com.ownclaw.privacy.Redactor(null);
        assertEquals("2 secrets removed, 1 identifier replaced", TaskChat.described(redactor.count("u1",
                "password=fake-pass-1\ntoken=fake-token-2\nmail alice@example.org", Map.of())));
        assertEquals("1 secret removed", TaskChat.described(redactor.count("u1", "password=fake-pass-1", Map.of())));
        assertEquals("3 identifiers replaced", TaskChat.described(redactor.count("u1",
                "a@example.org b@example.org c@example.org", Map.of())));
    }
}
