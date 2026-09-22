package com.ownclaw.agent;

import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolExecutionContext;
import com.ownclaw.agent.tools.ToolParam;
import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.agent.tools.ToolSchemas;
import com.ownclaw.llm.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the cloud model may call on unattended work.
 * <p>
 * The owner's architecture is "cloud orchestrates, local executes", and for seven months the
 * local tier executed nothing. Three prompt formulations failed to get a single delegation
 * chosen, because a scheduled task names the exact skills and order — "using daily_news_digest
 * skill, then ... Use smtp_send_email" — and a specific instruction beats a general preference
 * every time. Today both scheduled runs spent a quarter of a million cloud tokens on work with
 * no judgement in it.
 * <p>
 * So on unattended work the registry is withheld: the cloud can orchestrate and cannot execute.
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

    private static List<String> names(List<ToolSpec> specs) {
        return specs.stream().map(ToolSpec::name).toList();
    }

    @Test
    @DisplayName("attended work keeps the whole registry")
    void attendedIsUnchanged() {
        var specs = ToolSchemas.build(SpecialActionSchemas.ALL, REGISTRY, List.of());
        assertTrue(names(specs).contains("daily_news_digest"),
                "the user is waiting; a local step costs about a minute, so the cloud executes");
        assertTrue(names(specs).contains("smtp_send_email"));
    }

    @Test
    @DisplayName("unattended work offers orchestration only")
    void unattendedWithholdsTheRegistry() {
        var specs = ToolSchemas.build(SpecialActionSchemas.ALL, List.of(), List.of());
        var n = names(specs);
        assertFalse(n.contains("daily_news_digest"), "the cloud must not be able to execute it");
        assertFalse(n.contains("smtp_send_email"));
        assertTrue(n.contains(AgentAction.DELEGATE), "delegation is how the work gets done");
        assertTrue(n.contains(AgentAction.RESPOND), "it still has to be able to answer");
        assertTrue(n.contains(AgentAction.SKILL_CREATE),
                "a missing capability must still be buildable, or a gap becomes a dead end");
        assertTrue(n.contains(AgentAction.ASK_USER));
    }

    @Test
    @DisplayName("every special action survives the restriction")
    void specialActionsAllSurvive() {
        var n = names(ToolSchemas.build(SpecialActionSchemas.ALL, List.of(), List.of()));
        assertEquals(SpecialActionSchemas.ALL.size(), n.size(),
                "withholding the registry must not quietly drop an action the loop branches on");
    }
}
