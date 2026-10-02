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
            String prompt = all(engine.buildMessages(ctx, provider, new ThinkingEngine.StepMode(false, false, false)));
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
        String delegate = engine.toolsFor(ctx, new ThinkingEngine.StepMode(true, true, false)).stream()
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
        var mode = new ThinkingEngine.StepMode(false, false, false);
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
                    new ThinkingEngine.StepMode(nativeTools, false, false)).get(0).content();
            assertFalse(system.contains("1-2 sentences") || system.contains("sentence or two")
                    || system.contains("truncation"), system);
        }
    }

    @Test
    @DisplayName("once the provider declined a step as reasoning extraction, no protocol asks for words beside a call")
    void aDeclinedTaskIsAskedForNoWords() {
        var engine = new ThinkingEngine(new ToolRegistry(List.of()), new OwnClawConfig(), null);
        var ctx = new AgentContext("u1", "t1", "check the network");
        ctx.trajectory().record(new AgentAction("noop", Map.of(), "Checking the network first."),
                AgentObservation.success("noop", "nothing to report", Map.of(), 5));
        ctx.markDeclinedAsReasoning();
        for (String provider : List.of("anthropic", "openai")) {
            String prompt = all(engine.buildMessages(ctx, provider, new ThinkingEngine.StepMode(false, false, false)));
            assertTrue(prompt.contains("Single JSON: {\"tool\": \"name\", \"params\": {...}}"), prompt);
            assertFalse(prompt.contains("\"reasoning\"") || prompt.contains(ThinkingEngine.NARRATION)
                    || prompt.contains("Checking the network first."), prompt);
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
            String prompt = all(engine.buildMessages(ctx, "anthropic", new ThinkingEngine.StepMode(nativeTools, false, false)));
            assertTrue(prompt.contains("memory_manage action=recall"), prompt);
            assertFalse(prompt.contains("## Past Experience"), "no past task is put into the prompt by itself: " + prompt);
        }
        String text = all(engine.buildMessages(ctx, "anthropic", new ThinkingEngine.StepMode(false, false, false)));
        assertTrue(text.contains("memory_manage(action=store|list|delete|recall, [key], [content], [query])"), text);
    }

    @Test
    @DisplayName("what the model is told about past tasks is true in a chat follow-up: this chat's are shown, recall finds the rest")
    void pastTasksAreDescribedTruly() {
        var engine = new ThinkingEngine(new ToolRegistry(List.of()), new OwnClawConfig(), null);
        var ctx = new AgentContext("u1", "t1", "why did that fail?");
        ctx.setConversationSummary("### The conversation so far\nUSER: audit the routers\nASSISTANT: Done.\n"
                + "[OwnClaw's record of task 4b22f2c5, from its step log:\n1. ✓ router_audit]");
        ToolSpec memory = SpecialActionSchemas.ALL.stream()
                .filter(t -> AgentAction.MEMORY_MANAGE.equals(t.name())).findFirst().orElseThrow();
        for (boolean nativeTools : new boolean[] {true, false}) {
            String prompt = all(engine.buildMessages(ctx, "anthropic", new ThinkingEngine.StepMode(nativeTools, false, false)));
            assertTrue(prompt.contains("## Prior Context") && prompt.contains("record of task 4b22f2c5"),
                    "the premise: the request shows this chat's earlier task");
            for (String told : List.of(prompt, memory.description())) {
                assertFalse(told.contains("Past tasks are not shown to you"),
                        "said in the same request that shows one: " + told);
                assertTrue(told.contains("Of past tasks you are shown only this chat's, under Prior Context"), told);
            }
        }
    }

    @Test
    @DisplayName("every provider is told whether anyone is waiting, by the one per-step block, after the same static system prompt")
    void everyProviderGetsTheSameStepBlock() {
        var engine = new ThinkingEngine(new ToolRegistry(List.of()), new OwnClawConfig(), null);
        for (boolean unattended : new boolean[] {false, true}) {
            var ctx = new AgentContext("u1", "t1", "check the routers");
            ctx.setUnattended(unattended);
            ctx.setUserPreferences("Reports in Czech.");
            String attendance = unattended ? "- Attendance: NOBODY IS WAITING." : "- Attendance: THE USER IS WAITING";
            for (int steps = 0; steps < 2; steps++) {
                if (steps == 1) {
                    ctx.trajectory().record(new AgentAction("ping", Map.of("host", "192.0.2.1"), ""),
                            AgentObservation.success("ping", "up", Map.of(), 5));
                }
                for (boolean nativeTools : new boolean[] {true, false}) {
                    var mode = new ThinkingEngine.StepMode(nativeTools, false, false);
                    String system = engine.buildMessages(ctx, "anthropic", mode).get(0).content();
                    for (String provider : List.of("anthropic", "openai", "ollama")) {
                        List<LlmMessage> messages = engine.buildMessages(ctx, provider, mode);
                        assertEquals(system, messages.get(0).content(), provider + " gets another system prompt");
                        String last = messages.get(messages.size() - 1).content();
                        assertTrue(last.contains(attendance), provider + " is not told who is waiting: " + last);
                        assertTrue(last.contains("## Preferences\nReports in Czech."), provider + ": " + last);
                        assertEquals(steps == 1, last.contains("Next action? If done, use 'respond'."),
                                provider + ", " + steps + " steps: " + last);
                    }
                }
            }
        }
        // Mutation: give the other providers their own dynamic section again -> no attendance.
    }

    @Test
    @DisplayName("every provider's history shows what each step was called with, as the Anthropic replay does")
    void everyHistoryShowsTheArguments() {
        var engine = new ThinkingEngine(new ToolRegistry(List.of()), new OwnClawConfig(), null);
        var ctx = new AgentContext("u1", "t1", "send the report to the team");
        ctx.trajectory().record(new AgentAction("smtp_send_email",
                        Map.of("to", "team@example.org", "subject", "Weekly report", "body", "{{1}}"), ""),
                AgentObservation.success("smtp_send_email", "Sent", Map.of(), 12));
        var mode = new ThinkingEngine.StepMode(true, false, false);
        String replayed = engine.buildMessages(ctx, "anthropic", mode).stream()
                .filter(m -> m.role() == LlmMessage.Role.ASSISTANT).findFirst().orElseThrow().content();
        for (String provider : List.of("openai", "ollama")) {
            String history = all(engine.buildMessages(ctx, provider, mode));
            assertTrue(history.contains("[Step 1] " + replayed + "\n[smtp_send_email] OK (12ms)\nSent"),
                    provider + " is not shown the step as written: " + history);
            assertTrue(history.contains("team@example.org") && history.contains("{{1}}"), history);
        }
        // Mutation: render the history line as the tool's name alone -> the model cannot tell
        // whom it wrote to.
    }

    @Test
    @DisplayName("the prompt's own text -- system prompt, actions, schemas -- passes the privacy filter unchanged")
    void thePromptHasNothingToFilter() {
        // Every request carries it: a false positive here would take words out of every call.
        var engine = new ThinkingEngine(new ToolRegistry(List.of()), new OwnClawConfig(), null);
        var ctx = new AgentContext("u1", "t1", "do something");
        var redactor = new com.ownclaw.privacy.Redactor(null);
        for (boolean nativeTools : List.of(true, false)) {
            var mode = new ThinkingEngine.StepMode(nativeTools, false, false);
            var texts = new ArrayList<String>();
            for (LlmMessage m : engine.buildMessages(ctx, "anthropic", mode)) texts.add(m.content());
            for (ToolSpec t : engine.toolsFor(ctx, mode)) {
                texts.add(t.description());
                texts.add(String.valueOf(t.inputSchema()));
            }
            for (String text : texts) {
                var tally = new com.ownclaw.privacy.Redactor.Tally();
                assertEquals(text, redactor.filter("u1", text, Map.of(), tally), tally.secretsRemoved()
                        + " secrets, " + tally.identifiersReplaced() + " identifiers, or a placeholder's shape");
            }
        }
    }
}
