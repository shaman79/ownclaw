package com.ownclaw.agent;

import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolExecutionContext;
import com.ownclaw.agent.tools.ToolParam;
import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.agent.tools.ToolSchemas;
import com.ownclaw.llm.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Turning the live tool registry into JSON Schema.
 * <p>
 * The input is not trustworthy. Every tool is a Python skill the model wrote at runtime, and the
 * {@code type} strings come out of LLM-authored SKILL.yaml with no validation anywhere — so this
 * has to cope with whatever a model felt like writing that day, without letting one bad entry
 * reject the whole request and stall every step of every task.
 */
class ToolSchemasTest {

    private static Tool tool(String name, String description, Map<String, ToolParam> schema) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return description; }
            public Map<String, ToolParam> inputSchema() { return schema; }
            public ToolResult execute(Map<String, Object> p, ToolExecutionContext c) {
                return ToolResult.failure("not called");
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> props(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("properties");
    }

    @Test
    @DisplayName("required parameters land in required, optional ones do not")
    void requiredAndOptional() {
        var params = new LinkedHashMap<String, ToolParam>();
        params.put("url", ToolParam.required("string", "page to fetch"));
        params.put("selector", ToolParam.optional("string", "css selector"));

        var schema = ToolSchemas.toJsonSchema(params);
        assertEquals("object", schema.get("type"));
        assertEquals(List.of("url"), schema.get("required"));
        assertEquals(2, props(schema).size());
    }

    @Test
    @DisplayName("an empty required list is omitted rather than sent as []")
    void emptyRequiredOmitted() {
        var params = Map.of("q", ToolParam.optional("string", "query"));
        assertFalse(ToolSchemas.toJsonSchema(params).containsKey("required"),
                "providers disagree about what [] means and there is no reason to find out");
    }

    @Test
    @DisplayName("the type strings real skills actually contain are mapped")
    void realWorldTypes() {
        assertEquals("string",  ToolSchemas.normalizeType("str"));
        assertEquals("string",  ToolSchemas.normalizeType("String"));
        assertEquals("string",  ToolSchemas.normalizeType("text"));
        assertEquals("integer", ToolSchemas.normalizeType("int"));
        assertEquals("number",  ToolSchemas.normalizeType("float"));
        assertEquals("boolean", ToolSchemas.normalizeType("Boolean"));
        assertEquals("array",   ToolSchemas.normalizeType("List[str]"));
        assertEquals("array",   ToolSchemas.normalizeType("array<string>"));
        assertEquals("object",  ToolSchemas.normalizeType("dict"));
    }

    @Test
    @DisplayName("an unrecognised type omits the keyword instead of guessing string")
    void unknownTypeOmitsKeyword() {
        assertNull(ToolSchemas.normalizeType("DataFrame"));
        assertNull(ToolSchemas.normalizeType(""));
        assertNull(ToolSchemas.normalizeType(null));

        var schema = ToolSchemas.toJsonSchema(Map.of("df", ToolParam.required("DataFrame", "a frame")));
        var prop = (Map<?, ?>) props(schema).get("df");
        assertFalse(prop.containsKey("type"),
                "guessing string would make the provider reject the model's correct call; "
                        + "no type is valid JSON Schema and means 'anything'");
        assertEquals("a frame", prop.get("description"), "the description still has to survive");
    }

    @Test
    @DisplayName("a name the provider would refuse is skipped, not allowed to fail the request")
    void unsafeNameIsSkipped() {
        assertTrue(ToolSchemas.isApiSafeName("web_fetch_and_parse"));
        assertTrue(ToolSchemas.isApiSafeName("a"));
        assertFalse(ToolSchemas.isApiSafeName("x".repeat(70)), "too long for the API");
        assertFalse(ToolSchemas.isApiSafeName("has space"));
        assertFalse(ToolSchemas.isApiSafeName(""));
        assertFalse(ToolSchemas.isApiSafeName(null));

        var specs = ToolSchemas.build(List.of(),
                List.of(tool("x".repeat(70), "bad name", Map.of()),
                        tool("good_tool", "fine", Map.of())),
                List.of());
        assertEquals(List.of("good_tool"), specs.stream().map(ToolSpec::name).toList(),
                "one bad name must not reject the whole request and stall every task");
    }

    @Test
    @DisplayName("a skill cannot shadow a special action")
    void specialActionsWin() {
        var specs = ToolSchemas.build(SpecialActionSchemas.ALL,
                List.of(tool("respond", "a skill pretending to be the answer action", Map.of())),
                List.of());
        assertEquals(1, specs.stream().filter(s -> s.name().equals("respond")).count(),
                "sending the name twice rejects the request; and the loop branches on the "
                        + "sentinel first, so the skill was never reachable anyway");
        assertTrue(specs.get(0).description().contains("final answer"),
                "the special action is the one that survives");
    }

    @Test
    @DisplayName("network, side-effect and credential facts survive into the description")
    void descriptionCarriesTheFlags() {
        Tool t = new Tool() {
            public String name() { return "smtp_send_email"; }
            public String description() { return "Send an email."; }
            public Map<String, ToolParam> inputSchema() { return Map.of(); }
            public ToolResult execute(Map<String, Object> p, ToolExecutionContext c) {
                return ToolResult.failure("x");
            }
            @Override public boolean requiresNetwork() { return true; }
            @Override public boolean hasSideEffects() { return true; }
            @Override public List<String> requiredCredentials() { return List.of("SMTP_PASS", "MISSING_KEY"); }
        };
        String d = ToolSchemas.describe(t, List.of("SMTP_PASS"));
        assertTrue(d.contains("needs network"), d);
        assertTrue(d.contains("has side effects"), d);
        assertTrue(d.contains("SMTP_PASS available"), d);
        assertTrue(d.contains("MISSING_KEY NOT SET"),
                "JSON Schema has no field for this, and dropping it would be a quiet downgrade: " + d);
    }

    @Test
    @DisplayName("every special action the loop branches on is declared")
    void everySentinelIsDeclared() throws Exception {
        var declared = SpecialActionSchemas.ALL.stream().map(ToolSpec::name).toList();
        for (var f : AgentAction.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())
                    || f.getType() != String.class) continue;
            String sentinel = (String) f.get(null);
            assertTrue(declared.contains(sentinel),
                    "AgentLoop branches on '" + sentinel + "' but no schema declares it, so a "
                            + "model offered only these tools could never reach that branch");
        }
        for (ToolSpec s : SpecialActionSchemas.ALL) {
            assertFalse(s.description() == null || s.description().isBlank(),
                    s.name() + " has no description for the model to read");
        }
    }
}
