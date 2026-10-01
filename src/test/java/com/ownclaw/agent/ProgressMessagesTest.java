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
 * which step, which tool, how long, what it has cost, and what the model wrote beside the call --
 * one for each tool call of a delegation, and the local model's summary of each private result,
 * which only he may read. Saved in the task's own chat and shown live; never in a prompt.
 */
class ProgressMessagesTest {

    static final String STATEMENT = "Closing balance 48,213.07 CZK on account CZ65 0800 0000 1920 0014 5399, "
            + "statement of 30 September";
    static final String SUMMARY = "Zůstatek k 30. září je 48 213,07 Kč.";

    static final Tool PING = tool("ping", List.of(), p -> "pong");
    /** A skill that needs a credential: what it returns is PRIVATE. */
    static final Tool BANK = tool("bank_fetch", List.of("BANK_PASS"), p -> STATEMENT);

    /** A local model answering every call with {@code answer}, keeping what it was asked. */
    static final class Local implements LlmProvider {
        final List<List<LlmMessage>> asked = new CopyOnWriteArrayList<>();
        final Reply answer;
        Local(Reply answer) { this.answer = answer; }
        public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
            asked.add(List.copyOf(m));
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
        assertTrue(String.valueOf(rows.get(0).get("content")).matches("\\*\\*Step 1 · ping · [0-9.]+m?s · \\$\\d+\\.\\d\\d\\*\\*"
                + "\n\nThe first router answered; now the second\\."), String.valueOf(rows.get(0).get("content")));
        assertTrue(String.valueOf(rows.get(1).get("content")).matches(
                "\\*\\*Step 2 · skill_create router_check · [0-9.]+m?s · \\$\\d+\\.\\d\\d\\*\\*"),
                "no words beside the call, the header alone: " + rows.get(1).get("content"));
        for (var row : rows) {
            assertEquals(session, row.get("session_id"), "in the chat the task's message was saved in");
            assertEquals("{\"taskId\":\"" + r.taskId() + "\"}", row.get("metadata"));
            assertNull(row.get("private_content"));
        }
        var live = seen.stream().filter(m -> m.type() == StatusMessage.Type.PROGRESS_MESSAGE).toList();
        assertEquals(2, live.size());
        for (var m : live) {
            assertEquals(r.taskId(), m.taskId());
            assertEquals(session, m.data().get("sessionId"));
            assertNull(m.data().get("telegram"), "a web chat task's progress is not sent to Telegram");
        }
        assertEquals(rows.get(0).get("content"), live.get(0).text());
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
        assertFalse(prompt.contains("PROGRESS-NARRATION") || prompt.contains("Step 1 ·"), prompt);
        assertTrue(prompt.contains("ASSISTANT: It answers."), prompt);
    }

    @Test
    @DisplayName("a private result is summarised by the local model for the owner alone: the row's content holds none of it")
    void aPrivateResultIsSummarisedForTheOwner(@TempDir Path tmp) throws Exception {
        var local = new Local(c -> Replies.of(SUMMARY, 400, 30));
        var rig = new LoopRig(tmp, List.of(BANK), 600, local);
        String session = rig.chat.createSession("u1", "Bank");
        rig.cloud.think.add(call("bank_fetch", Map.of()));
        rig.cloud.think.add(respond("The statement is in."));
        var seen = rig.statuses();

        AgentResult r = rig.turn(session, "Jaký mám zůstatek?");
        var row = awaitRow(rig, "**Result 1 (bank_fetch)**");

        String content = String.valueOf(row.get("content"));
        assertEquals("**Result 1 (bank_fetch)** — summarised by your local model; private, shown only to you.", content);
        holdsNoWindowOf(content, STATEMENT);
        holdsNoWindowOf(content, SUMMARY);
        assertEquals("**Result 1 (bank_fetch)** — summarised by your local model, not seen by the cloud:\n\n" + SUMMARY,
                row.get("private_content"));
        assertEquals(session, row.get("session_id"));
        String asked = local.asked.get(0).get(1).content();
        assertTrue(asked.contains("Jaký mám zůstatek?") && asked.contains(STATEMENT),
                "the owner's message, for its language, and the result whole: " + asked);
        var live = seen.stream().filter(m -> m.type() == StatusMessage.Type.PROGRESS_MESSAGE
                && m.text().startsWith("**Result 1")).findFirst().orElseThrow();
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
        down.cloud.think.add(respond("ok"));
        down.turn(down.chat.createSession("u1", "Bank"), "fetch the statement");
        assertEquals("**Result 1 (bank_fetch)** — the local model could not summarise it: the local model is not "
                + "answering.", awaitRow(down, "**Result 1").get("content"));

        var failing = new LoopRig(tmp.resolve("failing"), List.of(BANK), 600,
                new Local(c -> { throw new LlmException("ollama", "HTTP 500: " + STATEMENT); }));
        failing.cloud.think.add(call("bank_fetch", Map.of()));
        failing.cloud.think.add(respond("ok"));
        failing.turn(failing.chat.createSession("u1", "Bank"), "fetch the statement");
        var row = awaitRow(failing, "**Result 1");
        assertEquals("**Result 1 (bank_fetch)** — the local model could not summarise it: it failed (LlmException).",
                row.get("content"), "its message can quote the result, so only its type");
        assertNull(row.get("private_content"));
    }

    @Test
    @DisplayName("the summaries are written beside the task: a slow one never holds up a step")
    void aSummaryNeverDelaysTheTask(@TempDir Path tmp) throws Exception {
        var release = new CountDownLatch(1);
        var local = new Local(c -> {
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return Replies.of(SUMMARY, 400, 30);
        });
        var rig = new LoopRig(tmp, List.of(BANK, PING), 600, local);
        rig.cloud.think.add(call("bank_fetch", Map.of()));
        rig.cloud.think.add(call("ping", Map.of()));
        rig.cloud.think.add(respond("Fetched and pinged."));

        AgentResult r = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> rig.turn(rig.chat.createSession("u1", "Bank"), "fetch and ping"));

        assertEquals("Fetched and pinged.", r.response(), "the task finished while its summary was being written");
        assertTrue(progress(rig).stream().noneMatch(row -> String.valueOf(row.get("content")).startsWith("**Result")));
        release.countDown();
        // Posted below the task's answer -- and maybe among the next task's rows -- it names its task.
        var row = awaitRow(rig, "**Result 1 (bank_fetch)** of your message “fetch and ping” — summarised");
        assertTrue(String.valueOf(row.get("private_content")).startsWith(
                "**Result 1 (bank_fetch)** of your message “fetch and ping” — summarised by your local model, "
                        + "not seen by the cloud:\n\n" + SUMMARY), "and it is posted when it is ready");
        // Mutation: name it alike before and after the task ended -> "Result 1" of which task?
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
        awaitRow(rig, "**Result 1 (bank_fetch)**");

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
    @DisplayName("a stopped task stops its summaries: the one being written ends, and says why")
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
        var row = awaitRow(rig, "**Result 1");
        assertEquals("**Result 1 (bank_fetch)** — the local model could not summarise it: the task was stopped.",
                row.get("content"));
        assertNull(row.get("private_content"));
    }

    @Test
    @DisplayName("each tool call of a delegation is announced in the chat as the local model's")
    void aDelegationsTurnsAreShown(@TempDir Path tmp) throws Exception {
        var local = new DelegationBehaviourTest.Scripted(DelegationBehaviourTest.call("ping", Map.of()),
                DelegationBehaviourTest.call("ping", Map.of("n", 2)), DelegationBehaviourTest.done("pinged twice"));
        var rig = new LoopRig(tmp, List.of(PING), 600, local);
        rig.cloud.think.add(call(AgentAction.DELEGATE, Map.of("goal", "Ping the router twice.", "tools", "ping")));
        rig.cloud.think.add(respond("It answered twice."));

        rig.turn(rig.chat.createSession("u1", "Router"), "ping the router twice");

        var contents = progress(rig).stream().map(row -> String.valueOf(row.get("content"))).toList();
        assertEquals(3, contents.size(), contents.toString());
        assertTrue(contents.get(0).startsWith("**Step 1 · delegate · "), contents.get(0));
        assertEquals("**Local model · turn 1 · ping**", contents.get(1));
        assertEquals("**Local model · turn 2 · ping**", contents.get(2));
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
}
