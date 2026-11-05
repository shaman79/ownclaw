package com.ownclaw.agent;

import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.LlmResponse;
import com.ownclaw.llm.OutputTruncated;
import com.ownclaw.llm.Replies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static com.ownclaw.agent.LoopRig.call;
import static com.ownclaw.agent.LoopRig.respond;
import static com.ownclaw.agent.TaskEndToEndTest.session;
import static com.ownclaw.agent.AssistantPartsTest.tool;
import static org.junit.jupiter.api.Assertions.*;

/** A delegation whose conversation outgrows the local model's window folds its results, as the task's steps do. */
class DelegationFitTest {

    static final String LOG = "203.0.113.7 GET /index.html 200\n".repeat(1_800) + "198.51.100.23 GET /admin 403\n";

    /** A local model on the text protocol, answering each call from a script of its own. */
    static final class Local implements LlmProvider {
        final List<List<LlmMessage>> asked = new CopyOnWriteArrayList<>();
        final Deque<Function<List<LlmMessage>, LlmResponse>> script = new ArrayDeque<>();

        public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
            asked.add(List.copyOf(m));
            var next = script.poll();
            if (next == null) throw new AssertionError("the script has no reply for local call " + asked.size());
            return next.apply(m);
        }

        public boolean isAvailable() { return true; }
        public String name() { return "ollama"; }

        void says(String text) {
            script.add(m -> Replies.of(text, 100, 10));
        }

        void tooLong(LlmResponse reply) {
            script.add(m -> {
                throw new OutputTruncated("ollama", OutputTruncated.Limit.CONTEXT_WINDOW, 262_144, reply);
            });
        }
    }

    private static String last(List<LlmMessage> messages) {
        return messages.get(messages.size() - 1).content();
    }

    @Test
    @DisplayName("a delegation too long for the window folds its largest result, asks the turn again, and reads the rest with read_result")
    void aDelegationFolds(@TempDir Path tmp) throws Exception {
        var local = new Local();
        local.says(DelegationBehaviourTest.call("read_access_log", Map.of()));
        local.tooLong(null);
        local.says(DelegationBehaviourTest.call("read_result", Map.of("result", 1, "find", "/admin")));
        local.says(DelegationBehaviourTest.call("read_result", Map.of("result", "1", "from", 56_992)));
        local.says(DelegationBehaviourTest.call("read_result", Map.of("result", 1, "length", 32)));
        local.says(DelegationBehaviourTest.done("198.51.100.23 was refused /admin"));
        var rig = new LoopRig(tmp, List.of(tool("read_access_log", List.of(), p -> LOG)), 600, local);
        rig.cloud.think.add(call(AgentAction.DELEGATE, Map.of("goal", "Who asked for /admin?", "tools", "read_access_log")));
        rig.cloud.think.add(respond("198.51.100.23"));

        AgentResult r = rig.turn(session(rig), "who asked for /admin?");

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals(6, local.asked.size(), "asked again after the fold, then read three times and finished");
        assertTrue(last(local.asked.get(1)).contains(LOG), "the turn that did not fit had it whole");
        String again = last(local.asked.get(2));
        assertFalse(again.contains(LOG), "folded");
        assertTrue(again.startsWith("Tool result {{1}} [read_access_log] SUCCESS:\n[Result {{1}} is 57,629 characters: "
                + "too long to show whole with the rest of this work in your context window, so it is folded and only "
                + "its first 2,000 are below."), again);
        assertTrue(again.contains("A tool you hand {{1}} is still given all of it.]\n" + LOG.substring(0, 2_000)), again);
        String found = last(local.asked.get(3));
        assertTrue(found.contains("\"/admin\" occurs in 1 place in result {{1}} (57,629 characters, case aside)"), found);
        assertTrue(found.contains("198.51.100.23 GET /admin 403"), found);
        String read = last(local.asked.get(4));
        assertTrue(read.startsWith("Result {{1}}, characters 56,992-57,629 of 57,629:\n" + LOG.substring(56_992)), read);
        assertTrue(String.join("\n", LoopRig.userParts(rig.cloud.calls("think").get(1)))
                .contains("198.51.100.23 was refused /admin"), "the delegation's answer reached the cloud");
        assertTrue(rig.jdbc.queryForList("SELECT content FROM conversations", String.class).stream()
                .anyMatch(c -> c.startsWith("📦 The task no longer fit the local model's context window, so the result "
                        + "of read_access_log (57,629 characters) is folded")), "the owner is told");
        // Mutations: fail the delegation on the window, as before -> 2 local calls, the cloud
        // takes over; count a read as a turn that ran nothing -> it ends after three reads.
    }

    @Test
    @DisplayName("a reply that filled the window, from a request that fitted, folds nothing: the delegation fails as before")
    void aRunawayReplyIsNotFolded(@TempDir Path tmp) throws Exception {
        var local = new Local();
        local.says(DelegationBehaviourTest.call("read_access_log", Map.of()));
        // 20,000 tokens read, the rest of the window written: the reply ran on, not the request.
        local.tooLong(Replies.of("", 20_000, 242_144, 0, 0, "length"));
        var rig = new LoopRig(tmp, List.of(tool("read_access_log", List.of(), p -> LOG)), 600, local);
        rig.cloud.think.add(call(AgentAction.DELEGATE, Map.of("goal", "Who asked for /admin?", "tools", "read_access_log")));
        rig.cloud.think.add(respond("The local model could not finish."));

        AgentResult r = rig.turn(session(rig), "who asked for /admin?");

        assertEquals(2, local.asked.size(), "not asked again: " + r.response());
        assertTrue(String.join("\n", LoopRig.userParts(rig.cloud.calls("think").get(1)))
                .contains("Local LLM call failed"), "the delegation failed and the cloud was told");
        // Mutation: fold whatever the cut -> asked again, and the script runs out.
    }
}
