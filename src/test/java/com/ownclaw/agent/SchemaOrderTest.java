package com.ownclaw.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.tools.ToolParam;
import com.ownclaw.agent.tools.ToolSchemas;
import com.ownclaw.llm.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The tools' JSON is the front of every request's cached prompt, so it must be the same bytes on
 * every run of the JVM. A map built with Map.of iterates in an order seeded anew at each start:
 * the delegate tool's call schema was one, and on some restarts the tools JSON came out different
 * and every chat's hour-long cache was missed. No map of a schema may be one; every map of more
 * than one key is in the order it was written.
 */
class SchemaOrderTest {

    /** Where in {@code tree} a map of more than one key iterates in no order of its own. */
    static List<String> unordered(Object tree, String at) {
        var out = new ArrayList<String>();
        if (tree instanceof Map<?, ?> map) {
            String type = map.getClass().getName();
            if (map.size() > 1 && type.startsWith("java.util.ImmutableCollections")) out.add(at + " (" + type + ")");
            for (var e : map.entrySet()) out.addAll(unordered(e.getValue(), at + "." + e.getKey()));
        } else if (tree instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) out.addAll(unordered(list.get(i), at + "[" + i + "]"));
        }
        return out;
    }

    @Test
    @DisplayName("every tool a step is offered -- the special actions, delegate's calls included, and a delegation's read_result -- has its maps in written order")
    void schemasAreOrdered() throws Exception {
        var engine = ChatCacheTest.engine();
        var tools = new ArrayList<ToolSpec>(SpecialActionSchemas.ALL);
        for (var mode : List.of(ChatCacheTest.CHEAPER, ChatCacheTest.FASTEST)) {
            var ctx = ChatCacheTest.turn("check the routers", List.of(), List.of("web_fetch"));
            engine.buildMessages(ctx, "anthropic", mode);
            tools.addAll(engine.toolsFor(ctx, mode));
        }
        tools.add(LocalExecutor.READ_RESULT);
        var problems = new ArrayList<String>();
        for (ToolSpec t : tools) problems.addAll(unordered(t.inputSchema(), t.name()));
        assertEquals(List.of(), problems, "a map whose order changes from one run to the next");
        // And the JSON of the delegate tool's call schema is written in that order.
        var delegate = tools.stream().filter(t -> AgentAction.DELEGATE.equals(t.name())).findFirst().orElseThrow();
        String json = new ObjectMapper().writeValueAsString(delegate.inputSchema());
        assertTrue(json.contains("\"properties\":{\"tool\":{\"type\":\"string\",\"description\":\"The tool's exact name.\"},"
                + "\"params\":{\"type\":\"object\""), json);
        // Mutation: build the call schema with Map.of again -> this names it.
    }

    @SuppressWarnings("unchecked")
    private static List<String> propertyNames(Map<String, Object> schema) {
        return new ArrayList<>(((Map<String, Object>) schema.get("properties")).keySet());
    }

    @Test
    @DisplayName("tool parameters go in written order; handed over in a map of no order of its own, in sorted order -- the same on every run")
    void parametersInAStableOrder() {
        assertEquals(List.of("result", "from", "length", "find"), propertyNames(LocalExecutor.READ_RESULT.inputSchema()),
                "a delegation's read_result, as written");
        var p = ToolParam.optional("string", "a parameter");
        var salted = Map.of("zeta", p, "alpha", p, "mu", p, "delta", p, "omega", p, "beta", p);
        assertEquals(List.of("alpha", "beta", "delta", "mu", "omega", "zeta"), propertyNames(ToolSchemas.toJsonSchema(salted)));
        var written = new LinkedHashMap<String, ToolParam>();
        for (String name : List.of("zeta", "alpha", "mu")) written.put(name, p);
        assertEquals(List.of("zeta", "alpha", "mu"), propertyNames(ToolSchemas.toJsonSchema(written)));
        // Mutation: read_result's parameters by Map.of again -> sorted, not as written; take a
        // Map.of's own order -> it differs from run to run (here, from the sorted one).
    }
}
