package com.ownclaw.agent;

import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the cloud is told when the owner is waiting: that he reads its words beside each call as
 * it works, and where the local model is the right tool, with its speed as facts. "Use it only
 * when it genuinely saves more than it costs" meant no attended task ever delegated: the cloud
 * wrote three skills in seventeen minutes to read router data it could not see.
 */
class AttendedGuidanceTest {

    private static final String SPEED = "reads about 100 tokens a second and writes about 8";

    @Test
    @DisplayName("on every provider and both protocols: the words beside a call are for the waiting user, and private data goes to the local model")
    void onEveryPath() {
        var engine = new ThinkingEngine(new ToolRegistry(List.of()), new OwnClawConfig(), null);
        var ctx = new AgentContext("u1", "t1", "why is the router slow?");
        for (String provider : List.of("anthropic", "openai", "ollama")) {
            for (boolean nativeTools : new boolean[] {true, false}) {
                var mode = new ThinkingEngine.StepMode(nativeTools, false, false);
                List<LlmMessage> messages = engine.buildMessages(ctx, provider, mode);
                String path = provider + (nativeTools ? ", native" : ", text") + ": ";
                String system = messages.get(0).content();
                String step = messages.get(messages.size() - 1).content();

                assertTrue(system.contains((nativeTools ? "the text you write beside a tool call is shown to them "
                        + "live in the chat: " : "'reasoning' is shown to them live in the chat: ")
                        + ThinkingEngine.NARRATION), path + system);
                assertFalse(system.contains("reasoning, not the answer"), path + system);
                assertTrue(system.contains("is read by the local model: to read, summarise, search, compare or "
                        + "answer a question about it, delegate and name its handle in the goal."), path + system);
                assertTrue(system.contains("Never create one only to read or summarise data -- delegate that."),
                        path + system);
                assertTrue(step.contains("THE USER IS WAITING") && step.contains(SPEED), path + step);
                assertFalse(step.contains("genuinely saves more than it costs"), path + step);

                String delegate = nativeTools ? engine.toolsFor(ctx, mode).stream()
                        .filter(s -> AgentAction.DELEGATE.equals(s.name())).map(ToolSpec::description)
                        .findFirst().orElseThrow() : system;
                assertTrue(delegate.contains(SPEED), path + delegate);
                assertTrue(delegate.contains("is given to it whole"), path + delegate);
                assertFalse(delegate.contains("max_steps") || delegate.contains("nobody is waiting")
                        || delegate.contains("cannot see earlier ones"), path + delegate);
            }
        }
    }

    @Test
    @DisplayName("a delegation takes no step ceiling: the schema has no max_steps")
    void noCeilingToAskFor() {
        ToolSpec delegate = SpecialActionSchemas.ALL.stream()
                .filter(s -> AgentAction.DELEGATE.equals(s.name())).findFirst().orElseThrow();
        assertFalse(String.valueOf(delegate.inputSchema()).contains("max_steps"), String.valueOf(delegate.inputSchema()));
    }

    @Test
    @DisplayName("unattended work is told what it was told before: nobody waits, and the local model is preferred")
    void unattendedIsUnchanged() {
        var engine = new ThinkingEngine(new ToolRegistry(List.of()), new OwnClawConfig(), null);
        var ctx = new AgentContext("u1", "t1", "the morning digest");
        ctx.setUnattended(true);
        var messages = engine.buildMessages(ctx, "anthropic", new ThinkingEngine.StepMode(true, false, false));
        String step = messages.get(messages.size() - 1).content();
        assertTrue(step.contains("- Attendance: NOBODY IS WAITING. This was started by the scheduler or sent to the "
                + "background; the answer is delivered to the chat whenever it is ready. Minutes are free here. "
                + "Prefer 'delegate' for anything the local model can do"), step);
    }
}
