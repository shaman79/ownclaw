package com.ownclaw.agent;

import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolExecutionContext;
import com.ownclaw.agent.tools.ToolParam;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the cloud is told about the tools, the task and itself -- all of it, on every step.
 * <p>
 * The text protocol showed the 60 tools a word-overlap score ranked highest in full and the rest
 * as bare names, the first 50 of them; a model that cannot see what a skill does builds another.
 * Delegate's catalogue cut each skill's description at 110 characters. A provider other than
 * Anthropic got a shortened system prompt from the second step on. And the text protocol told the
 * model to keep its reasoning to a sentence or two, because long reasoning "risks truncation".
 */
class WholePromptTest {

    private static Tool skill(String name, String description) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return description; }
            public Map<String, ToolParam> inputSchema() { return Map.of(); }
            public ToolResult execute(Map<String, Object> p, ToolExecutionContext c) {
                return ToolResult.failure("not run");
            }
        };
    }

    /** 90 skills, each with a description far past any old cut, ending in a marker of its own. */
    private static List<Tool> library() {
        var tools = new ArrayList<Tool>();
        for (int i = 0; i < 90; i++) {
            tools.add(skill(String.format("skill_%02d", i),
                    "Does task number " + i + " in a long and careful way. ".repeat(8) + "END-" + i));
        }
        return tools;
    }

    private static String all(List<LlmMessage> messages) {
        return messages.stream().map(LlmMessage::content).reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    @DisplayName("the text protocol lists every tool with its whole description, on both renderers")
    void everyToolWhole() {
        var engine = new ThinkingEngine(new ToolRegistry(library()), new OwnClawConfig(), null);
        var ctx = new AgentContext("u1", "t1", "do something");
        for (String provider : List.of("anthropic", "openai")) {
            String prompt = all(engine.buildMessages(ctx, provider, new ThinkingEngine.StepMode(false, false)));
            for (Tool t : library()) {
                assertTrue(prompt.contains(t.name() + ": " + t.description()),
                        provider + " shows " + t.name() + " without its whole description");
            }
            assertFalse(prompt.contains("names only") || prompt.contains("more)") || prompt.contains("omitted"),
                    provider + " hides part of the library");
        }
    }

    @Test
    @DisplayName("delegate's catalogue carries every skill's whole description")
    void theCatalogueIsWhole() {
        var engine = new ThinkingEngine(new ToolRegistry(library()), new OwnClawConfig(), null);
        var ctx = new AgentContext("u1", "t1", "the morning digest");
        ctx.setUnattended(true);
        String delegate = engine.toolsFor(ctx, new ThinkingEngine.StepMode(true, true)).stream()
                .filter(s -> AgentAction.DELEGATE.equals(s.name())).map(ToolSpec::description)
                .findFirst().orElseThrow();
        for (Tool t : library()) {
            assertTrue(delegate.contains("- " + t.name() + ": " + t.description()), t.name());
        }
    }

    @Test
    @DisplayName("every provider gets the whole system prompt on every step")
    void theWholeSystemPromptOnEveryStep() {
        var engine = new ThinkingEngine(new ToolRegistry(List.of()), new OwnClawConfig(), null);
        var ctx = new AgentContext("u1", "t1", "do something");
        var mode = new ThinkingEngine.StepMode(false, false);
        String first = engine.buildMessages(ctx, "openai", mode).get(0).content();
        ctx.trajectory().record(new AgentAction("fetch", Map.of(), "fetching"),
                AgentObservation.success("fetch", "page", Map.of(), 5));
        String later = engine.buildMessages(ctx, "openai", mode).get(0).content();

        for (String section : List.of("## Identity", "## Actions", "## Credentials", "## Memory",
                "## Output", "## Rules")) {
            assertTrue(first.contains(section) && later.contains(section), section + " dropped on a later step");
        }
    }

    @Test
    @DisplayName("nothing tells the model to keep its reasoning short")
    void noLengthInstruction() {
        var engine = new ThinkingEngine(new ToolRegistry(List.of()), new OwnClawConfig(), null);
        var ctx = new AgentContext("u1", "t1", "do something");
        for (boolean nativeTools : new boolean[] {true, false}) {
            String system = engine.buildMessages(ctx, "anthropic",
                    new ThinkingEngine.StepMode(nativeTools, false)).get(0).content();
            assertFalse(system.contains("1-2 sentences") || system.contains("sentence or two")
                    || system.contains("truncation"), system);
        }
    }

    @Test
    @DisplayName("past tasks are recalled on request, and the request is documented where the model reads it")
    void recallIsDocumented() {
        ToolSpec memory = SpecialActionSchemas.ALL.stream()
                .filter(s -> AgentAction.MEMORY_MANAGE.equals(s.name())).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) memory.inputSchema().get("properties");
        assertTrue(props.containsKey("query"), "recall takes a query");
        assertTrue(String.valueOf(props.get("action")).contains("recall"), String.valueOf(props.get("action")));
        assertTrue(memory.description().contains("recall"), memory.description());

        var engine = new ThinkingEngine(new ToolRegistry(List.of()), new OwnClawConfig(), null);
        var ctx = new AgentContext("u1", "t1", "do it like last time");
        for (boolean nativeTools : new boolean[] {true, false}) {
            String prompt = all(engine.buildMessages(ctx, "anthropic", new ThinkingEngine.StepMode(nativeTools, false)));
            assertTrue(prompt.contains("memory_manage action=recall"), prompt);
            assertFalse(prompt.contains("## Past Experience"), "no past task is put into the prompt by itself: " + prompt);
        }
        String text = all(engine.buildMessages(ctx, "anthropic", new ThinkingEngine.StepMode(false, false)));
        assertTrue(text.contains("memory_manage(action=store|list|delete|recall, [key], [content], [query])"), text);
    }
}
