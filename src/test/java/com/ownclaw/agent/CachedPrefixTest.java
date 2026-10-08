package com.ownclaw.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ownclaw.agent.tools.Tool;
import com.ownclaw.conversation.ChatOptions;
import com.ownclaw.llm.ScriptedAnthropic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.ownclaw.agent.AssistantPartsTest.tool;
import static com.ownclaw.agent.LoopRig.*;
import static com.ownclaw.llm.ScriptedAnthropic.calling;
import static com.ownclaw.llm.ScriptedAnthropic.declining;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What a task sends Anthropic, request by request, through the real loop, thinking engine,
 * gateway and Anthropic provider: the tools and the system prompt -- the front of the cached
 * prefix -- the same bytes on every step, and the conversation only growing, so each step reads
 * what the last one wrote to the cache.
 * <p>
 * On 2026-10-08 a $4.60 task on a Cheaper chat wrote its whole conversation to the cache twice,
 * $2.83 of it: when its delegation failed and the registry came back as a tools array of its own
 * (9 tools, then 52) with the system prompt's rule about them, and when a step declined as
 * reasoning extraction was asked again under a system prompt that asked for no words.
 */
class CachedPrefixTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("across a failed delegation on a Cheaper chat and a step declined as reasoning extraction, every request keeps its system block, tools and cache marks, and only grows")
    void thePrefixHolds(@TempDir Path tmp) throws Exception {
        var ran = new AtomicInteger();
        Tool router = tool("router_status", List.of(), p -> {
            ran.incrementAndGet();
            return "the router is up";
        });
        var local = LocalModeTest.localModel();
        // A local tier that can take delegated work, as production's does when it is healthy.
        var rig = new LoopRig(tmp, List.of(router, tool("weather_lookup", List.of(), p -> "sunny"),
                tool("smtp_send_email", List.of(), p -> "sent")), 600, local,
                (registry, config, llm) -> new ThinkingEngine(registry, config, llm) {
                    @Override
                    boolean localTierReady(AgentContext context) {
                        return true;
                    }
                });
        // One of the owner's usual skills: offered from a task's first step where the cloud runs
        // skills, deferred like the rest where it orchestrates.
        rig.jdbc.update("INSERT INTO skill_usage (tool_name, user_id, task_id, success, duration_ms) "
                + "VALUES ('smtp_send_email', 'u1', 't0', 1, 5)");
        // With words beside the calls, which the steps after a decline still show as they were.
        var api = ScriptedAnthropic.replying(
                calling("Looking for the skills first.", AgentAction.FIND_TOOLS, "{\"query\": \"weather\"}"),
                calling("Handing the check to the local model.", AgentAction.DELEGATE,
                        "{\"goal\": \"check the router's status\", \"tools\": \"router_status\"}"),
                calling("The local model could not; checking it myself.", "router_status", "{}"),
                declining("reasoning_extraction"),
                calling(AgentAction.RESPOND, "{\"message\": \"The router is up.\"}"));
        // Every think call is the real provider's request, from what the gateway let through.
        for (int i = 0; i < 5; i++) {
            rig.cloud.think.add(c -> api.provider().chat(rig.cloud.calls.getLast().messages(), c));
        }
        // The local model gives up without running anything: the delegation fails.
        local.think.add(call("done", Map.of("summary", "I could not check it.")));

        AgentResult r = rig.turn(LocalModeTest.session(rig), "check the router", new ChatOptions("cheaper", null));

        // ── what the task did ──
        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals("The router is up.", r.response());
        var steps = r.trajectory().steps();
        assertEquals(List.of(AgentAction.FIND_TOOLS, AgentAction.DELEGATE, "router_status"),
                steps.stream().map(t -> t.action().tool()).toList());
        assertFalse(steps.get(1).observation().success(), "the premise: the delegation failed");
        assertEquals(1, ran.get(), "after it, the cloud ran the skill itself");

        List<JsonNode> sent = new ArrayList<>();
        for (String body : api.bodies()) sent.add(JSON.readTree(body));
        assertEquals(5, sent.size(), "the declined step was asked again");

        // ── the front of the prefix: the same bytes in every request ──
        JsonNode first = sent.getFirst();
        for (int i = 1; i < sent.size(); i++) {
            assertEquals(first.path("system"), sent.get(i).path("system"), "request " + (i + 1) + " changed the system block");
            assertEquals(notDeferred(first), notDeferred(sent.get(i)), "request " + (i + 1) + " changed the tools");
            assertEquals(withoutMessages(first), withoutMessages(sent.get(i)),
                    "request " + (i + 1) + " changed something else before the messages");
        }
        assertEquals(1, api.betas().stream().distinct().count(), "the beta headers changed: " + api.betas());
        assertTrue(api.betas().getFirst().contains("mid-conversation-tool-changes-2026-07-01"), api.betas().getFirst());
        var deferred = new ArrayList<String>();
        first.path("tools").forEach(t -> { if (t.path("defer_loading").asBoolean()) deferred.add(t.path("name").asText()); });
        assertEquals(List.of("smtp_send_email", "router_status", "weather_lookup"), deferred,
                "every skill is sent from the first request on, deferred, the usual one first, and too");

        // ── the cache marks, where they were ──
        for (int i = 0; i < sent.size(); i++) {
            JsonNode body = sent.get(i);
            var expected = new ArrayList<>(List.of("/tools/" + lastNotDeferred(body) + " 1h", "/system/0 1h",
                    "/messages/0/content/0 1h"));
            int newest = newestUserTurn(body);
            if (newest > 0) {
                assertEquals("assistant", body.path("messages").get(newest - 1).path("role").asText());
                expected.add("/messages/" + (newest - 1) + "/content/0 5m");
            }
            assertEquals(expected, marks(body, ""), "request " + (i + 1));
        }

        // ── the conversation only grows: what one request cached, the next sends again ──
        for (int i = 0; i + 1 < sent.size(); i++) {
            JsonNode before = sent.get(i).path("messages"), after = sent.get(i + 1).path("messages");
            for (int m = 0; m < newestUserTurn(sent.get(i)); m++) {
                assertEquals(unmarked(before.get(m)), unmarked(after.get(m)),
                        "request " + (i + 2) + " rewrote message " + m + " of the conversation request " + (i + 1) + " cached");
            }
        }

        // ── what changed went at the end ──
        for (JsonNode body : sent.subList(0, 2)) {
            assertEquals(List.of(), additions(body), "nothing offered while the cloud orchestrates");
        }
        List<String> offered = additions(sent.get(2));
        assertEquals(List.of("smtp_send_email", "router_status", "weather_lookup"), offered,
                "once the delegation failed: the usual skill, the one the request fits and the one find_tools found");
        JsonNode valve = sent.get(2).path("messages");
        assertEquals("system", valve.get(valve.size() - 1).path("role").asText(), "after the steps it had read");
        assertTrue(newestUser(sent.get(2)).contains("a delegation of this task has failed"), newestUser(sent.get(2)));
        assertTrue(newestUser(sent.get(3)).contains(ThinkingEngine.NARRATION), "the premise: words asked for");
        String quiet = newestUser(sent.get(4));
        assertTrue(quiet.contains(ThinkingEngine.beside(true, true)) && !quiet.contains(ThinkingEngine.NARRATION), quiet);
        assertFalse(sent.get(4).toString().contains(ThinkingEngine.NARRATION), "no words asked for anywhere");
        assertTrue(sent.get(4).path("messages").toString().contains("Looking for the skills first."),
                "the words beside the earlier calls are shown as they were cached");

        // Mutation: the valve hands the registry back as tools of their own -> the tools differ
        // from request 3 on. The quiet retry's instruction in the system prompt -> the system
        // block differs at request 5. An addition placed where it was made -> request 3 inserts
        // a message request 2 cached.
    }

    /** The tools sent that are not deferred: the part of the array in the cached prefix. */
    static JsonNode notDeferred(JsonNode body) {
        ArrayNode out = JSON.createArrayNode();
        body.path("tools").forEach(t -> { if (!t.path("defer_loading").asBoolean()) out.add(t); });
        return out;
    }

    static int lastNotDeferred(JsonNode body) {
        int last = -1;
        for (int i = 0; i < body.path("tools").size(); i++) {
            if (!body.path("tools").get(i).path("defer_loading").asBoolean()) last = i;
        }
        return last;
    }

    static JsonNode withoutMessages(JsonNode body) {
        ObjectNode copy = body.deepCopy();
        copy.remove("messages");
        return copy;
    }

    static int newestUserTurn(JsonNode body) {
        JsonNode messages = body.path("messages");
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).path("role").asText())) return i;
        }
        throw new AssertionError("no user turn: " + body);
    }

    static String newestUser(JsonNode body) {
        return body.path("messages").get(newestUserTurn(body)).path("content").toString();
    }

    /** Every cache mark in the body, as its JSON pointer and how long it lives. */
    static List<String> marks(JsonNode node, String at) {
        var out = new ArrayList<String>();
        if (node.has("cache_control")) {
            out.add(at + " " + (node.path("cache_control").path("ttl").asText("").equals("1h") ? "1h" : "5m"));
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> out.addAll(marks(e.getValue(), at + "/" + e.getKey())));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) out.addAll(marks(node.get(i), at + "/" + i));
        }
        // Tools, system, messages: the order the API reads them in.
        out.sort((a, b) -> Integer.compare(rank(a), rank(b)));
        return out;
    }

    private static int rank(String mark) {
        return mark.startsWith("/tools") ? 0 : mark.startsWith("/system") ? 1 : 2;
    }

    /**
     * A message as the model reads it: without its cache mark, and a text in one block the same
     * as the text alone -- the provider turns the one it marks into a block.
     */
    static JsonNode unmarked(JsonNode message) {
        ObjectNode copy = message.deepCopy();
        JsonNode content = copy.path("content");
        if (content.isArray()) {
            content.forEach(block -> ((ObjectNode) block).remove("cache_control"));
            if (content.size() == 1 && "text".equals(content.get(0).path("type").asText())) {
                copy.put("content", content.get(0).path("text").asText());
            }
        }
        return copy;
    }

    /** The tools the request's tool additions offer, in order. */
    static List<String> additions(JsonNode body) {
        var out = new ArrayList<String>();
        for (JsonNode m : body.path("messages")) {
            if (!"system".equals(m.path("role").asText())) continue;
            for (JsonNode block : m.path("content")) {
                if ("tool_addition".equals(block.path("type").asText())) out.add(block.path("tool").path("name").asText());
            }
        }
        return out;
    }
}
