package com.ownclaw.agent;

import com.ownclaw.agent.tools.Tool;
import com.ownclaw.core.ResultDelivery;
import com.ownclaw.core.ScheduledTaskService;
import com.ownclaw.interfaces.CommandHandler;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.Replies;
import com.ownclaw.llm.ToolCall;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.ownclaw.agent.AssistantPartsTest.tool;
import static com.ownclaw.agent.LoopRig.call;
import static com.ownclaw.agent.LoopRig.respond;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What the cloud is sent of the words a model writes beside its calls, through the real loop and
 * thinking engine, request by request. Anthropic's classifier declines a request that elicits the
 * model's reasoning in the reply (reasoning extraction), and the declined call is billed: from 14
 * to 18 Nov 2026 it declined 7 of 50 cloud calls, in every self-repair and one chat. Each earlier
 * step was replayed as {"reasoning": the words it wrote beside the call, "tool", "params"}; over
 * two weeks the retry that still replayed those words was declined 9 times out of 10, and the one
 * without them never. So no request replays them, from a task's first step; they stay in the
 * trajectory, in the owner's chat and on the task page. And where nobody is waiting, no step the
 * cloud takes asks for words beside its call at all.
 */
class EarlierWordsNotReplayedTest {

    static final Tool NOOP = tool("noop", List.of(), p -> "nothing to report");

    static final List<String> WORDS = List.of("First I am checking the gateway.",
            "The gateway answers; DNS is next.", "DNS resolves too; summing up.");

    /** The model calls {@code noop} with these words beside the call. */
    static LoopRig.Reply saying(String words, int n) {
        return c -> Replies.of(words, 1_000, 100, 0, 0, "tool_use",
                List.of(new ToolCall("c-noop-" + n, "noop", Map.of("n", n))));
    }

    static String all(LoopRig.Call call) {
        return String.join("\n", call.messages().stream().map(LlmMessage::content).toList());
    }

    static List<String> assistantTurns(LoopRig.Call call) {
        return call.messages().stream().filter(m -> m.role() == LlmMessage.Role.ASSISTANT).map(LlmMessage::content).toList();
    }

    /** The newest user message of a request: it ends with the per-step block. A TOOLS message can follow it. */
    static String lastUser(LoopRig.Call call) {
        return call.messages().stream().filter(m -> m.role() == LlmMessage.Role.USER).reduce((a, b) -> b)
                .orElseThrow().content();
    }

    /** Three steps with words beside each call, then the answer. */
    static void script(LoopRig rig) {
        for (int i = 0; i < WORDS.size(); i++) rig.cloud.think.add(saying(WORDS.get(i), i + 1));
        rig.cloud.think.add(respond("all quiet"));
    }

    /** No request of {@code thinks} replays a word written beside a call; each earlier step is its tool and arguments. */
    static void assertNothingReplayed(List<LoopRig.Call> thinks) {
        assertEquals(4, thinks.size());
        for (int i = 0; i < thinks.size(); i++) {
            String request = all(thinks.get(i));
            for (String words : WORDS) {
                assertFalse(request.contains(words), "request " + (i + 1) + " replays \"" + words + "\": " + request);
            }
            assertFalse(request.contains("\"reasoning\":"), "request " + (i + 1) + " replays a step under a key called "
                    + "reasoning: " + request);
            var expected = new ArrayList<String>();
            for (int step = 1; step <= i; step++) expected.add("{\"tool\":\"noop\",\"params\":{\"n\":" + step + "}}");
            assertEquals(expected, assistantTurns(thinks.get(i)), "request " + (i + 1));
            // The system prompt the provider caches: the same bytes on every step of the task.
            assertEquals(thinks.get(0).messages().get(0), thinks.get(i).messages().get(0), "request " + (i + 1)
                    + " changed the system prompt");
        }
    }

    @Test
    @DisplayName("a chat: no request replays the words the model wrote beside an earlier call, from the first step to the last, while the chat shows each and the trajectory keeps them")
    void aChatsWordsAreShownAndKeptButNeverReplayed(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(NOOP));
        script(rig);
        AgentResult r = rig.turn(rig.chat.createSession("u1", "Network"), "check the network");

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        var thinks = rig.cloud.calls("think");
        assertNothingReplayed(thinks);
        // The owner is waiting: every step asks for the progress update, as before.
        for (var think : thinks) {
            assertTrue(lastUser(think).contains(ThinkingEngine.beside(true, false)), lastUser(think));
        }
        // Shown in the chat as the task goes, each under its step ...
        List<String> rows = rig.jdbc.queryForList("SELECT content FROM conversations WHERE role = 'progress' "
                + "ORDER BY rowid", String.class);
        for (int i = 0; i < WORDS.size(); i++) {
            String header = "☁️ Step " + (i + 1) + " · noop";
            String words = WORDS.get(i);
            assertTrue(rows.stream().anyMatch(c -> c.startsWith(header) && c.endsWith(words)), header + ": " + rows);
        }
        // ... and kept, for the task page.
        var steps = r.trajectory().steps();
        for (int i = 0; i < WORDS.size(); i++) assertEquals(WORDS.get(i), steps.get(i).action().reasoning());
        // Mutation: the replay's assistant turn written with the words (actionText(true) in
        // buildAnthropicMessages) -> the second request holds the first step's.
    }

    @Test
    @DisplayName("unattended work: nothing is asked for beside the call from the first step, and what a model writes there all the same is kept and never replayed")
    void unattendedWorkAsksForNothingAndReplaysNothing(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(NOOP));
        script(rig);
        AgentResult r = rig.loop.executeFull("u1", "check the network", true);

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        var thinks = rig.cloud.calls("think");
        assertNothingReplayed(thinks);
        for (int i = 0; i < thinks.size(); i++) {
            String step = lastUser(thinks.get(i));
            assertTrue(step.contains("NOBODY IS WAITING") && step.contains(ThinkingEngine.beside(true, true)),
                    "request " + (i + 1) + ": " + step);
            assertFalse(all(thinks.get(i)).contains(ThinkingEngine.NARRATION), "request " + (i + 1) + " asks for words");
        }
        var steps = r.trajectory().steps();
        for (int i = 0; i < WORDS.size(); i++) assertEquals(WORDS.get(i), steps.get(i).action().reasoning());
        // Mutation: quiet() without its test of unattended work -> the first request asks for a
        // progress update nobody reads.
    }

    @Test
    @DisplayName("/bg, a scheduled run and a self-repair are that work: each one's every request asks for nothing beside the call")
    void backgroundScheduledAndRepairAreUnattended(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(NOOP, tool("daily_menu_fetcher", List.of(), p -> "menus")));
        var results = new LinkedBlockingQueue<StatusMessage>();
        rig.emitter.subscribe("u1", results, m -> {
            if (m.type() == StatusMessage.Type.RESULT) results.add(m);
        });
        var delivery = new ResultDelivery(rig.chat, rig.emitter);
        rig.queue.start();
        try {
            // /bg, typed in a chat.
            var commands = new CommandHandler(rig.registry, rig.chat, null, null, rig.vault, rig.vault.grants(),
                    rig.queue, null, null, null, null, rig.cancellation, delivery, null, null, null);
            rig.cloud.think.add(call("noop", Map.of()));
            rig.cloud.think.add(respond("done in the background"));
            commands.handle("u1", "/bg check the network", rig.chat.createSession("u1", "Network")).orElseThrow();
            assertNotNull(results.poll(60, TimeUnit.SECONDS), "the /bg result was not delivered");

            // A scheduled run.
            var scheduler = new ScheduledTaskService(rig.jdbc, rig.queue, rig.emitter, rig.events, rig.chat, delivery);
            rig.cloud.think.add(call("noop", Map.of()));
            rig.cloud.think.add(respond("done on schedule"));
            scheduler.scheduleDeferred("u1", "check the network", Instant.now().minusSeconds(60));
            scheduler.pollDueTasks();
            assertNotNull(results.poll(60, TimeUnit.SECONDS), "the scheduled result was not delivered");

            // A self-repair, as the queue runs one.
            rig.cloud.think.add(call(AgentAction.SKILL_MANAGE, Map.of("action", "list")));
            rig.cloud.think.add(respond("Nothing to change."));
            AgentResult repair = rig.queue.submitRepair("u1", "Repair the skill 'daily_menu_fetcher'.",
                    SkillSelfRepairTest.job("daily_menu_fetcher", false)).get(60, TimeUnit.SECONDS);
            assertEquals(AgentResult.TerminationReason.COMPLETED, repair.terminationReason(), repair.response());
        } finally {
            rig.queue.stop();
        }

        var thinks = rig.cloud.calls("think");
        assertEquals(6, thinks.size());
        String[] what = {"/bg", "/bg", "a scheduled run", "a scheduled run", "a self-repair", "a self-repair"};
        for (int i = 0; i < thinks.size(); i++) {
            String step = lastUser(thinks.get(i));
            assertTrue(step.contains("NOBODY IS WAITING") && step.contains(ThinkingEngine.beside(true, true)),
                    what[i] + ": " + step);
            assertFalse(all(thinks.get(i)).contains(ThinkingEngine.NARRATION), what[i] + " asks for words");
        }
        // Mutations: the queue running background work as attended (unattended only above the
        // background priority) -> /bg's and the scheduled run's requests say the user is waiting;
        // a repair executed as attended -> its requests do.
    }
}
