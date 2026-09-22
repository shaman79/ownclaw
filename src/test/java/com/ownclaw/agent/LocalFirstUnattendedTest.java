package com.ownclaw.agent;

import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolExecutionContext;
import com.ownclaw.agent.tools.ToolParam;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.agent.tools.ToolSchemas;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the cloud model may call on unattended work, and whether the prompt agrees with it.
 * <p>
 * The owner's architecture is "cloud orchestrates, local executes", and not one delegation has
 * ever succeeded on a scheduled run that chose it by itself. It was chosen on 19, 20 and 21
 * September and failed every time on a context window one step too small; the window was raised,
 * and the next day it stopped being chosen at all. Rewriting the task descriptions is not the
 * fix: an unattended task naming no skills, pure local work, was still done by the cloud. So on
 * unattended work the registry is withheld: the cloud can orchestrate and cannot execute.
 * <p>
 * The first version of this test only exercised {@link ToolSchemas#build}, and would have passed
 * unchanged with the restriction wide open — the prompt still listed every withheld skill and
 * still taught the text envelope that bypasses the tools array entirely. These tests assert the
 * things that actually decide the run.
 */
class LocalFirstUnattendedTest {

    private static Tool skill(String name, String description) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return description; }
            public Map<String, ToolParam> inputSchema() { return Map.of(); }
            public ToolResult execute(Map<String, Object> p, ToolExecutionContext c) {
                return ToolResult.failure("not called in this test");
            }
        };
    }

    private static final List<Tool> REGISTRY = List.of(
            skill("daily_news_digest", "Fetch and format a news digest."),
            skill("smtp_send_email", "Send an email."));

    private static ThinkingEngine engine() {
        ToolRegistry registry = new ToolRegistry(REGISTRY);
        return new ThinkingEngine(registry, new ToolSelector(registry), new OwnClawConfig(), null);
    }

    private static AgentContext unattended() {
        AgentContext ctx = new AgentContext("u1", "t1",
                "Send the morning digest using daily_news_digest, then smtp_send_email.");
        ctx.setUnattended(true);
        return ctx;
    }

    /**
     * Everything the model is shown, as one string — system prompt included.
     * <p>
     * The first version of this helper called {@code buildAnthropicMessages} only, which omits
     * the system prompt. Every "the prompt does not say X" assertion then passed for the wrong
     * reason, since X lives in the system prompt. The text-protocol test below is the control:
     * it asserts the prose IS present, and it failed, which is the only reason the hole showed.
     */
    private static String prompt(ThinkingEngine engine, AgentContext ctx,
                                 ThinkingEngine.StepMode mode) {
        List<LlmMessage> messages = engine.buildMessages(ctx, "anthropic", mode);
        return messages.stream().map(LlmMessage::content).reduce("", (a, b) -> a + "\n" + b);
    }

    private static List<String> names(List<ToolSpec> specs) {
        return specs.stream().map(ToolSpec::name).toList();
    }

    // ── the offered set ──

    @Test
    @DisplayName("attended work keeps the whole registry")
    void attendedIsUnchanged() {
        var n = names(ToolSchemas.build(SpecialActionSchemas.ALL, REGISTRY, List.of()));
        assertTrue(n.contains("daily_news_digest"),
                "the user is waiting; a local step costs about a minute, so the cloud executes");
        assertTrue(n.contains("smtp_send_email"));
    }

    @Test
    @DisplayName("unattended work offers orchestration only")
    void unattendedWithholdsTheRegistry() {
        var n = names(ToolSchemas.build(SpecialActionSchemas.ALL, List.of(), List.of()));
        assertFalse(n.contains("daily_news_digest"), "the cloud must not be able to execute it");
        assertFalse(n.contains("smtp_send_email"));
        assertTrue(n.contains(AgentAction.DELEGATE), "delegation is how the work gets done");
        assertTrue(n.contains(AgentAction.RESPOND), "it still has to be able to answer");
        assertTrue(n.contains(AgentAction.SKILL_CREATE),
                "a missing capability must still be buildable, or a gap becomes a dead end");
        assertEquals(SpecialActionSchemas.ALL.size(), n.size(),
                "withholding the registry must not quietly drop an action the loop branches on");
    }

    // ── the prompt has to agree with the offered set ──

    @Test
    @DisplayName("under localFirst the prompt does not offer a skill the tools array withholds")
    void promptDoesNotContradictTheToolsArray() {
        String text = prompt(engine(), unattended(), new ThinkingEngine.StepMode(true, true));

        assertFalse(text.contains("## Tools"),
                "the actionable manifest is what tells the model it can call these");
        assertTrue(text.contains("daily_news_digest"),
                "it must still know the skill exists, or it will rebuild it with skill_create");
        assertTrue(text.contains("cannot call these yourself"),
                "knowing a skill exists and being able to call it are different things");
        assertTrue(text.contains("delegate"), "it has to be told how the work gets done");
    }

    @Test
    @DisplayName("native tools: the JSON envelope is not taught alongside a tools array")
    void nativeToolsDoNotTeachTheTextEnvelope() {
        String text = prompt(engine(), unattended(), new ThinkingEngine.StepMode(true, false));
        assertFalse(text.contains("Single JSON:"),
                "this instruction IS the escape hatch: a model told to emit {tool, params} as "
                        + "text will, and the parser accepts it, and the loop runs it");
        assertFalse(text.contains("## Actions"),
                "the tools array already carries every action; describing them twice costs "
                        + "tokens and lets the two copies drift apart");
    }

    @Test
    @DisplayName("the text protocol still gets the full prose prompt")
    void textProtocolIsUnchanged() {
        String text = prompt(engine(), unattended(), new ThinkingEngine.StepMode(false, false));
        assertTrue(text.contains("Single JSON:"), "without a tools array, prose is the protocol");
        assertTrue(text.contains("## Actions"));
        assertTrue(text.contains("## Tools"));
    }

    // ── skill_create must not ask for code it discards ──

    @Test
    @DisplayName("skill_create does not ask the model to write the code")
    void skillCreateDoesNotDemandCode() {
        ToolSpec create = SpecialActionSchemas.ALL.stream()
                .filter(s -> AgentAction.SKILL_CREATE.equals(s.name()))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) create.inputSchema().get("properties");
        assertFalse(props.containsKey("code"),
                "AgentLoop generates the code with the cloud and overwrites this field before "
                        + "SkillManager sees it, so asking for it spends output tokens on a "
                        + "Python module that is thrown away");
        assertTrue(props.containsKey("description"));
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) create.inputSchema().get("required");
        assertFalse(required.contains("code"));
    }
}
