package com.ownclaw.agent;

import com.ownclaw.agent.tools.Tool;
import com.ownclaw.conversation.ChatOptions;
import com.ownclaw.core.Inbox;
import com.ownclaw.core.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.ownclaw.agent.LoopRig.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The owner's OK before a task's first change ({@link Approval}): the task asks in its chat and
 * waits for his answer there, runs the rest without asking after a yes, stops asking for the day
 * on "always", does not run what he declines, and never asks when nobody is there to answer.
 */
@Timeout(60)
class ApprovalTest {

    // ── what a reply says ──

    @Test
    @DisplayName("an OK is a reply of yes/ok words only; anything with a condition or a no is not")
    void readsTheReply() {
        for (String yes : List.of("yes", "ok", "sure", "go ahead", "yes please", "ano", "jo", "pokračuj")) {
            assertEquals(Approval.Reply.YES, Approval.read(yes), yes);
        }
        for (String always : List.of("always", "vždy", "always, thanks")) {
            assertEquals(Approval.Reply.ALWAYS, Approval.read(always), always);
        }
        for (String no : List.of("no", "not yet", "yes but only step 1", "wait", "skip the delete",
                "why?", "do 1 and 2 only", "", "n")) {
            assertEquals(Approval.Reply.NO, Approval.read(no), no);
        }
    }

    // ── the inbox takes the answer ──

    private static UserMessage msg(String text) {
        return new UserMessage("u1", "A", "row", text, List.of(), TaskChat.Channel.WEB, ChatOptions.NONE, r -> {});
    }

    @Test
    @DisplayName("the first message sent after the question is its answer; one sent before is left for the task to read")
    void theInboxTakesTheAnswer() throws Exception {
        var inbox = new Inbox(TaskChat.Channel.WEB, ChatOptions.NONE);
        inbox.offer(msg("read this while you work"), 1);   // before the question
        inbox.startAsking();
        var answer = CompletableFuture.supplyAsync(() -> {
            try { return inbox.awaitAnswer(5_000, () -> false); }
            catch (InterruptedException e) { throw new IllegalStateException(e); }
        });
        Thread.sleep(50);
        assertTrue(inbox.offer(msg("yes"), 2), "the answer is taken");
        assertEquals("yes", answer.get(5, TimeUnit.SECONDS).text());
        assertEquals(List.of("read this while you work"), inbox.drain().stream().map(UserMessage::text).toList(),
                "the message sent before the question is still the task's to read");
    }

    @Test
    @DisplayName("no answer within the wait returns nothing; a close ends the wait")
    void theWaitEnds() throws Exception {
        var a = new Inbox(TaskChat.Channel.WEB, ChatOptions.NONE);
        a.startAsking();
        assertNull(a.awaitAnswer(120, () -> false), "nothing came");
        var b = new Inbox(TaskChat.Channel.WEB, ChatOptions.NONE);
        b.startAsking();
        var waiting = CompletableFuture.supplyAsync(() -> {
            try { return b.awaitAnswer(5_000, () -> false); }
            catch (InterruptedException e) { throw new IllegalStateException(e); }
        });
        Thread.sleep(50);
        b.close((m, o) -> {});
        assertNull(waiting.get(5, TimeUnit.SECONDS), "the task ended while it waited");
    }

    // ── end to end, through the real loop ──

    static final DelegationBehaviourTest.FakeTool DEPLOY = new DelegationBehaviourTest.FakeTool("deploy", true, List.of(),
            p -> com.ownclaw.agent.tools.ToolResult.success("deployed"));

    /** The question row a task posts before its first change, once it appears; fails if it does not. */
    private static String question(LoopRig rig) throws Exception {
        for (int i = 0; i < 200; i++) {
            var rows = rig.jdbc.queryForList(
                    "SELECT content FROM conversations WHERE role = 'progress' AND content LIKE '%Before I change anything%'",
                    String.class);
            if (!rows.isEmpty()) return rows.get(0);
            Thread.sleep(25);
        }
        throw new AssertionError("the task never asked before its change");
    }

    /** Run a chat turn on its own thread -- it blocks until the task ends -- and give its result. */
    private static CompletableFuture<AgentResult> turnAsync(LoopRig rig, String session, String text) {
        return CompletableFuture.supplyAsync(() -> rig.turn(session, text, ChatOptions.NONE));
    }

    private static void answer(LoopRig rig, String session, String text) {
        rig.queue.steer(new UserMessage("u1", session, "ans", text, List.of(),
                TaskChat.Channel.WEB, ChatOptions.NONE, r -> {}));
    }

    @Test
    @DisplayName("a task asks before its first change and runs it on a yes; the change is shown as it was written")
    void asksThenRunsOnYes(@TempDir Path tmp) throws Exception {
        Approval.forgetAlways();
        var rig = new LoopRig(tmp, List.of(DEPLOY));
        rig.cloud.think.add(call("deploy", Map.of("target", "forum", "cmd", "systemctl restart")));
        rig.cloud.think.add(respond("deployed the forum"));
        String session = rig.chat.createSession("u1", "Ops");

        var done = turnAsync(rig, session, "restart the forum");
        String q = question(rig);
        assertTrue(q.contains("`deploy`") && q.contains("systemctl restart"), "the call as written is shown: " + q);
        assertEquals(0, DEPLOY.calls.size(), "nothing ran before the owner answered");
        answer(rig, session, "yes");

        assertEquals("deployed the forum", done.get(10, TimeUnit.SECONDS).response());
        assertEquals(1, DEPLOY.calls.size(), "the change ran after the yes");
    }

    @Test
    @DisplayName("a change the owner declines is not run; his words reach the model, which goes on")
    void doesNotRunWhatIsDeclined(@TempDir Path tmp) throws Exception {
        Approval.forgetAlways();
        var deploy = new DelegationBehaviourTest.FakeTool("deploy", true, List.of(),
                p -> com.ownclaw.agent.tools.ToolResult.success("deployed"));
        var seen = new AtomicReference<String>("");
        var cloud = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var rig = new LoopRig(tmp, List.of(deploy));
        rig.cloud.think.add(call("deploy", Map.of("target", "forum")));
        rig.cloud.think.add(respond("left it as it is"));
        String session = rig.chat.createSession("u1", "Ops");

        var done = turnAsync(rig, session, "restart the forum");
        question(rig);
        answer(rig, session, "no, leave the forum alone");

        assertEquals("left it as it is", done.get(10, TimeUnit.SECONDS).response());
        assertEquals(0, deploy.calls.size(), "the declined change did not run");
        String trace = rig.cloud.calls("think").stream()
                .flatMap(c -> c.messages().stream()).map(com.ownclaw.llm.LlmMessage::content)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(trace.contains("the owner did not OK it") && trace.contains("leave the forum alone"),
                "the model is told, in his words: " + trace);
    }

    @Test
    @DisplayName("\"always\" runs this change and stops the asking in this chat: the next change does not ask")
    void alwaysStopsTheAsking(@TempDir Path tmp) throws Exception {
        Approval.forgetAlways();
        var deploy = new DelegationBehaviourTest.FakeTool("deploy", true, List.of(),
                p -> com.ownclaw.agent.tools.ToolResult.success("deployed"));
        var rig = new LoopRig(tmp, List.of(deploy));
        rig.cloud.think.add(call("deploy", Map.of("step", "1")));
        rig.cloud.think.add(call("deploy", Map.of("step", "2")));
        rig.cloud.think.add(respond("both done"));
        String session = rig.chat.createSession("u1", "Ops");

        var done = turnAsync(rig, session, "do the two steps");
        question(rig);
        answer(rig, session, "always");

        assertEquals("both done", done.get(10, TimeUnit.SECONDS).response());
        assertEquals(2, deploy.calls.size(), "the second change ran without asking again");
        assertEquals(1, rig.jdbc.queryForList(
                "SELECT content FROM conversations WHERE role = 'progress' AND content LIKE '%Before I change anything%'",
                String.class).size(), "asked once, not for the second change");
    }

    @Test
    @DisplayName("unattended work changes things without asking: nobody is there to answer")
    void unattendedNeverAsks(@TempDir Path tmp) throws Exception {
        Approval.forgetAlways();
        var deploy = new DelegationBehaviourTest.FakeTool("deploy", true, List.of(),
                p -> com.ownclaw.agent.tools.ToolResult.success("deployed"));
        var rig = new LoopRig(tmp, List.of(deploy));
        rig.cloud.think.add(call("deploy", Map.of("target", "forum")));
        rig.cloud.think.add(respond("done"));
        String row = rig.chat.saveMessage("u1", rig.chat.createSession("u1", "Scheduled"), "user", "nightly deploy");

        AgentResult r = rig.loop.executeFull("u1", "nightly deploy", true, row, List.of(), TaskChat.Channel.WEB);

        assertEquals("done", r.response());
        assertEquals(1, deploy.calls.size(), "it ran, unasked");
        assertTrue(rig.jdbc.queryForList(
                "SELECT content FROM conversations WHERE role = 'progress' AND content LIKE '%Before I change anything%'",
                String.class).isEmpty(), "no question was posted");
    }

    @Test
    @DisplayName("a delegation whose plan names a change asks before it starts, showing the goal")
    void aDelegationAsksBeforeItStarts(@TempDir Path tmp) throws Exception {
        Approval.forgetAlways();
        var deploy = new DelegationBehaviourTest.FakeTool("deploy", true, List.of(),
                p -> com.ownclaw.agent.tools.ToolResult.success("deployed"));
        var local = new DelegationBehaviourTest.Scripted(
                DelegationBehaviourTest.call("deploy", Map.of("target", "forum")),
                DelegationBehaviourTest.done("restarted"));
        var rig = new LoopRig(tmp, List.of(deploy), 600, local);
        rig.cloud.think.add(call(AgentAction.DELEGATE, Map.of("goal", "Restart the forum service.", "tools", "deploy")));
        rig.cloud.think.add(respond("the forum is back"));
        String session = rig.chat.createSession("u1", "Ops");

        var done = turnAsync(rig, session, "restart the forum");
        String q = question(rig);
        assertTrue(q.contains("delegation to the local model") && q.contains("Restart the forum service."), q);
        assertEquals(0, deploy.calls.size(), "the delegation did not start before the answer");
        answer(rig, session, "yes");

        assertEquals("the forum is back", done.get(10, TimeUnit.SECONDS).response());
        assertEquals(1, deploy.calls.size());
    }
}
