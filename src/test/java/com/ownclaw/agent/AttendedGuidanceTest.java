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

                // In the per-step block, which can ask for none once the provider has declined a
                // step as reasoning extraction, with the cached system prompt as it was.
                assertTrue(step.contains((nativeTools ? "the text you write beside a tool call is shown to them "
                        + "live in the chat: " : "'reasoning' is shown to them live in the chat: ")
                        + ThinkingEngine.NARRATION), path + step);
                assertFalse(system.contains(ThinkingEngine.NARRATION), path + system);
                assertFalse(step.contains("reasoning, not the answer"), path + step);
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

    @Test
    @DisplayName("where nobody waits, a step the cloud takes asks for nothing beside its call, on both protocols and from the first step; the local model's is asked as before; and the system prompt is the chat's, byte for byte")
    void unattendedCloudStepsAskForNothingBesideTheCall() {
        var engine = new ThinkingEngine(new ToolRegistry(List.of()), new OwnClawConfig(), null);
        for (boolean nativeTools : new boolean[] {true, false}) {
            var waited = new AgentContext("u1", "t1", "the morning digest");
            var nobody = new AgentContext("u1", "t2", "the morning digest");
            nobody.setUnattended(true);
            for (String provider : List.of("anthropic", "openai")) {
                var cloud = new ThinkingEngine.StepMode(nativeTools, false, false);
                var chat = engine.buildMessages(waited, provider, cloud);
                var run = engine.buildMessages(nobody, provider, cloud);
                String path = provider + (nativeTools ? ", native" : ", text") + ": ";
                String step = run.get(run.size() - 1).content();
                assertTrue(step.contains(ThinkingEngine.beside(nativeTools, true)), path + step);
                assertFalse(run.stream().anyMatch(m -> m.content().contains(ThinkingEngine.NARRATION)), path + step);
                assertTrue(chat.get(chat.size() - 1).content().contains(ThinkingEngine.NARRATION), path + "a chat is asked");
                // What is asked beside a call is the per-step block's: the system prompt the provider
                // caches is one text for attended and unattended work.
                assertEquals(chat.get(0).content(), run.get(0).content(), path + "the system prompt differs");
            }
            // The local model running the task itself: no provider's check reads its replies, and what
            // it writes beside a call is in the history it reads of its own steps.
            var local = engine.buildMessages(nobody, "ollama", new ThinkingEngine.StepMode(nativeTools, false, true));
            assertTrue(local.get(local.size() - 1).content().contains(ThinkingEngine.NARRATION),
                    local.get(local.size() - 1).content());
        }
        // Mutation: quiet() without its unattended clause -> the cloud's step on unattended work asks
        // for the progress update.
    }
}
