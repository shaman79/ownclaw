package com.ownclaw.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ownclaw.agent.tools.ToolParam;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.llm.CloudGateway;
import com.ownclaw.llm.EgressLedger;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.LlmResponse;
import com.ownclaw.llm.Replies;
import com.ownclaw.llm.RequestBodies;
import com.ownclaw.llm.ToolCall;
import com.ownclaw.llm.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A task of seven steps as each provider is sent its eighth think request, compared with a recording
 * of the same task ({@code src/test/resources/golden/think-request.json}): what the engine hands
 * the gateway for Anthropic, what the gateway hands the provider and keeps on the ledger, what the
 * model reads of Anthropic's request, and the whole request body OpenAI and the local model are
 * sent.
 * <p>
 * The recording was made on 2026-11-18 by the code as it was before the newest result of a think
 * call became a cached block of its own (AnthropicProvider#requestBody): that change moves a cache
 * mark and splits one message of Anthropic's request into two blocks, and may change no character
 * any model reads, nothing the gateway filters or records, and nothing of another provider's
 * request. How Anthropic's request is cut into blocks and where its cache marks stand is not in
 * the recording: AnthropicCacheTest and StepCacheTest say that.
 * <p>
 * It holds the task's conversation -- the chat, the task, every step and what the loop said, the
 * tools by name and where each was offered -- and every setting of the two whole request bodies.
 * It does not hold the words of the system prompt, of the tools' descriptions or of the per-step
 * block: those are other rules' to change, a word at a time, and the test says of them only that
 * every stage of a request holds the text the engine wrote ({@link Aside}).
 * <p>
 * A change that is meant to alter how a task's conversation is sent -- how a step is replayed,
 * where a tool is offered, what a provider is sent beside the messages -- records it again, and
 * its commit shows in the recording's diff exactly what changed for each provider: run this test
 * with {@code -Dgolden.record=true} (through the gate: {@code JAVA_TOOL_OPTIONS=-Dgolden.record=true}).
 * Never to make an unexplained difference pass.
 */
class ThinkRequestGoldenTest {

    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    static final String RECORDING = "golden/think-request.json";

    static final ThinkingEngine.StepMode CLOUD = new ThinkingEngine.StepMode(true, false, false);
    static final ThinkingEngine.StepMode LOCAL = new ThinkingEngine.StepMode(true, false, true);

    /** A vault value, which no request may carry. */
    static final String ROUTER_PASSWORD = "tr0ub4dor-and-3";

    static ToolRegistry registry() {
        return new ToolRegistry(List.of(
                ToolFinderTest.skill("smtp_send_email", "Send an email through the configured SMTP server. Returns the message id.",
                        Map.of("to", ToolParam.required("string", "Recipient address"))),
                ToolFinderTest.skill("openwrt_run", "Run a command on an OpenWrt router over SSH and return its output.",
                        Map.of("host", ToolParam.required("string", "Router address"))),
                ToolFinderTest.skill("openwrtWifiConfig", "Read the wireless configuration of every router.", Map.of()),
                ToolFinderTest.skill("web_fetch", "Fetch a web page and return its text.", Map.of())));
    }

    /** A turn of a chat, with the owner's facts, his usual skill and a vault that holds two keys. */
    static AgentContext task() {
        var ctx = new AgentContext("u1", "t-golden", "Check the routers' wifi and mail the report to jan@example.org.");
        List<String> chat = List.of("USER: which routers do we have?\n",
                "ASSISTANT: Two: the gateway and the one in the attic.\n");
        ctx.setConversationSummary(AgentContext.CONVERSATION_HEADER + String.join("", chat).strip());
        ctx.setConversation(chat);
        ctx.setUserPreferences("The NAS is at 192.0.2.20. (the owner said this)");
        ctx.setUsualTools(List.of("smtp_send_email"));
        ctx.setCredentialKeys(List.of("ROUTER_PASSWORD", "SMTP_PASSWORD"));
        ctx.setSecretValues(Map.of("ROUTER_PASSWORD", ROUTER_PASSWORD));
        return ctx;
    }

    /** What a router's configuration read holds that the gateway rewrites, and bytes no text should be split at. */
    static final String WIFI = "config wifi-iface 'default_radio0'\n"
            + "\toption ssid 'Example-Home'\n"
            + "\toption key 'correct horse battery staple'\n"
            + "\toption macaddr '02:00:5e:10:20:30'\n"
            + "admin contact: jan@example.org, uplink 203.0.113.7\n"
            + "the router's password is " + ROUTER_PASSWORD + "\n"
            + "a page quoted the prompt's own markers:" + LlmMessage.CACHE_BOUNDARY + "and" + LlmMessage.HISTORY_BLOCK
            + "and" + LlmMessage.HISTORY_END + "and" + LlmMessage.image(0) + "and a lone \u0001.\n"
            + "Příliš žluťoučký kůň úpěl ďábelské ódy.\n";

    static final String EMPTY_REPLY = "Your previous reply was empty (stop reason: end_turn): no text and no tool "
            + "call, so nothing was run.\n\nContinue from where the task stands.";

    /** A call's arguments in the order written, as a parsed reply holds them: Map.of's order changes from run to run. */
    static Map<String, Object> params(String... pairs) {
        var out = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) out.put(pairs[i], pairs[i + 1]);
        return out;
    }

    /**
     * The task's steps, each as the loop records one: a reply that could not be used before any
     * action, a search that offers a skill the task did not start with, a failure, a reflection, a
     * sent mail -- and last, so that the newest message of the request holds them, a result the
     * gateway rewrites, which quotes the prompt's own markers, and a message the owner sent while
     * the task worked. The model wrote nothing beside its calls: what a replayed call shows of such
     * words is another rule's, and no part of this recording.
     */
    static final List<Consumer<AgentContext>> STEPS = List.of(
            ctx -> ctx.trajectory().record(new AgentAction(ThinkingEngine.THINKING, Map.of(), EMPTY_REPLY),
                    AgentObservation.failure(ThinkingEngine.THINKING, EMPTY_REPLY, 0)),
            ctx -> {
                // A skill the task did not start with: offered by a tool addition after this step.
                ctx.addTaskTools(List.of("web_fetch"));
                ctx.trajectory().record(new AgentAction(AgentAction.FIND_TOOLS, Map.of("query", "web page"), ""),
                        AgentObservation.success(AgentAction.FIND_TOOLS, "1 skill matches 'web page':\n"
                                + "- web_fetch: Fetch a web page and return its text.", Map.of(), 3));
            },
            ctx -> ctx.trajectory().record(new AgentAction("openwrt_run", params("host", "attic.example.org",
                            "command", "uci show wireless"), ""),
                    AgentObservation.failure("openwrt_run", "ssh: connect to host attic.example.org port 22: "
                            + "Connection refused", 5_003)),
            ctx -> ctx.trajectory().record(new AgentAction("_reflection", Map.of(), "System-injected reflection"),
                    AgentObservation.failure("_reflection", "REFLECT: the last call failed. Try another way to reach "
                            + "the router, or report what you have.", 0)),
            ctx -> ctx.trajectory().record(new AgentAction("smtp_send_email", params("to", "jan@example.org",
                            "subject", "The attic router is down", "body", "{{2}}"), ""),
                    AgentObservation.success("smtp_send_email", "Message <20261118.1@mail.example.org> accepted for "
                            + "jan@example.org", Map.of(), 812)),
            ctx -> ctx.trajectory().record(new AgentAction("openwrtWifiConfig", Map.of(), ""),
                    AgentObservation.success("openwrtWifiConfig", WIFI, Map.of(), 2_417)),
            ctx -> ctx.trajectory().record(new AgentAction(AgentLoop.FROM_THE_OWNER_STEP, Map.of(),
                            "A message the user sent while the task worked"),
                    AgentObservation.success(AgentLoop.FROM_THE_OWNER_STEP,
                            AgentLoop.FROM_THE_OWNER + "the guest network too, please", Map.of(), 0)));

    /** A cloud provider that keeps what the gateway hands it. */
    static final class Kept implements LlmProvider {
        final String name;
        List<LlmMessage> messages;
        LlmRequestConfig config;

        Kept(String name) { this.name = name; }

        @Override
        public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
            messages = m;
            config = c;
            return Replies.of("", 100, 10, 0, 0, "tool_use",
                    List.of(new ToolCall("c-1", AgentAction.RESPOND, Map.of("message", "done"))));
        }

        @Override public boolean isAvailable() { return true; }
        @Override public boolean supportsTools() { return true; }
        @Override public String name() { return name; }
        @Override public String model() { return "a-model"; }
    }

    /** The task's last think request as the engine builds it: every step's request built in turn, as the loop asks. */
    record Request(List<LlmMessage> messages, LlmRequestConfig config) {}

    static Request lastRequest(String provider, ThinkingEngine.StepMode mode) {
        var engine = new ThinkingEngine(registry(), new OwnClawConfig(), null);
        AgentContext ctx = task();
        for (int step = 0; ; step++) {
            List<LlmMessage> messages = engine.buildMessages(ctx, provider, mode);
            LlmRequestConfig config = new LlmRequestConfig(null, null, false).withEgress(ctx.egress("think"))
                    .withEffort(ctx.options().effort()).withTools(engine.toolsFor(ctx, mode));
            if (step == STEPS.size()) return new Request(messages, config);
            STEPS.get(step).accept(ctx);
        }
    }

    /** {@code request} through the real gateway to a cloud provider of this name: what it was handed, and the ledger's row. */
    record Sent(Kept provider, EgressLedger.Row row) {}

    static Sent throughTheGateway(String provider, Request request) {
        var kept = new Kept(provider);
        var config = new OwnClawConfig();
        config.getMentor().setProvider(provider);
        var rows = new ArrayList<EgressLedger.Row>();
        new CloudGateway(kept, kept, config, rows::add, null).chat(request.messages(), request.config());
        assertEquals(1, rows.size());
        return new Sent(kept, rows.getFirst());
    }

    // ── what is compared ──

    /** How the per-step block opens after the newest result, for every provider. */
    static final String STEP_OPENS = "\n\n---\n## Environment\n";

    /** What the recording holds where a text stands that is not its own to pin. */
    static final String THE_SYSTEM_PROMPT = "<the system prompt>", THE_STEP = "\n\n---\n<the per-step block>";

    /**
     * The two texts of a request that the recording does not hold: the system prompt, and the
     * per-step block after the newest result. Their words are other rules' to change, and such a
     * change is no change to how a task's steps are sent. Each is kept here instead, as one stage
     * of the request held it, so the test can say every stage held the text the engine wrote.
     */
    static final class Aside {
        String systemPrompt, step;

        /** A system message's {@code content}: kept, and the recording's word for it. */
        String system(String content) {
            systemPrompt = content;
            return THE_SYSTEM_PROMPT;
        }

        /** The newest message's {@code content} with its per-step block kept aside. */
        String newest(String content) {
            int at = content.lastIndexOf(STEP_OPENS);
            assertTrue(at > 0, "the newest message ends with the per-step block: " + content);
            step = content.substring(at);
            return content.substring(0, at) + THE_STEP;
        }
    }

    /** A text, a line to an element, so the recording's diff reads line by line. */
    static JsonNode lines(String text) {
        ArrayNode out = JSON.createArrayNode();
        if (text != null) for (String line : text.split("\n", -1)) out.add(line);
        return out;
    }

    /** Every text of {@code node}, its lines joined again, one after another: for a test of what is in it. */
    static String text(JsonNode node) {
        var sb = new StringBuilder();
        if (node.isArray() && !node.isEmpty() && node.get(0).isTextual()) {
            var parts = new ArrayList<String>();
            node.forEach(line -> parts.add(line.asText()));
            return sb.append(String.join("\n", parts)).append('\n').toString();
        }
        node.forEach(child -> sb.append(text(child)));
        return sb.toString();
    }

    /** {@code node} with every string of more than one line as its {@link #lines}. */
    static JsonNode readable(JsonNode node) {
        if (node.isTextual()) {
            return node.asText().indexOf('\n') < 0 ? node : JSON.createObjectNode().set("lines", lines(node.asText()));
        }
        if (node.isArray()) {
            ArrayNode out = JSON.createArrayNode();
            node.forEach(n -> out.add(readable(n)));
            return out;
        }
        if (node.isObject()) {
            ObjectNode out = JSON.createObjectNode();
            node.fields().forEachRemaining(e -> out.set(e.getKey(), readable(e.getValue())));
            return out;
        }
        return node;
    }

    /** The messages as a stage of the request holds them: every one whole, but for what {@code aside} keeps. */
    static ArrayNode messages(List<LlmMessage> messages, Aside aside) {
        ArrayNode out = JSON.createArrayNode();
        int newest = -1;
        for (int i = 0; i < messages.size(); i++) if (messages.get(i).role() == LlmMessage.Role.USER) newest = i;
        for (int i = 0; i < messages.size(); i++) {
            LlmMessage m = messages.get(i);
            ObjectNode o = out.addObject();
            o.put("role", m.role().apiValue());
            o.put("authored", m.authored());
            o.put("marked", m.marked());
            o.put("images", m.images().size());
            o.set("content", lines(m.role() == LlmMessage.Role.SYSTEM ? aside.system(m.content())
                    : i == newest ? aside.newest(m.content()) : m.content()));
        }
        return out;
    }

    /** The tools by name, in order, and which are deferred and which the code's own: their words are not recorded. */
    static ArrayNode tools(List<ToolSpec> tools) {
        ArrayNode out = JSON.createArrayNode();
        for (ToolSpec t : tools) {
            out.addObject().put("name", t.name()).put("deferred", t.deferred()).put("authored", t.authored());
        }
        return out;
    }

    /**
     * The ledger's row: what the filter counted, every part's kind, and the length and hash of
     * each part of the conversation -- not of the system prompt, a tool or the newest message,
     * whose text the recording does not hold whole.
     */
    static ObjectNode ledger(EgressLedger.Row row, List<LlmMessage> handed) {
        ObjectNode out = JSON.createObjectNode();
        out.put("decision", row.decision().name());
        out.put("purpose", row.purpose());
        out.put("tools", row.toolCount());
        out.put("secretsRemoved", row.secretsRemoved());
        out.put("identifiersReplaced", row.identifiersReplaced());
        ArrayNode parts = out.putArray("parts");
        for (EgressLedger.Part p : row.parts()) {
            ObjectNode o = parts.addObject();
            o.put("index", p.index());
            o.put("kind", p.kind());
            boolean ofTheConversation = p.index() > 0 && p.index() < handed.size()
                    && !handed.get(p.index()).content().contains(STEP_OPENS);
            if (!ofTheConversation) continue;
            o.put("chars", p.chars());
            o.put("sha256_16", p.sha256_16());
        }
        return out;
    }

    /**
     * Anthropic's request as the model reads it: the tools by name, and each message's text, its
     * blocks joined -- a tool addition as the names it offers.
     */
    static ObjectNode read(JsonNode body, Aside aside) {
        ObjectNode out = JSON.createObjectNode();
        var system = new StringBuilder();
        body.path("system").forEach(b -> system.append(b.path("text").asText()));
        out.put("system", aside.system(system.toString()));
        ArrayNode tools = out.putArray("tools");
        for (JsonNode t : body.path("tools")) {
            tools.addObject().put("name", t.path("name").asText()).put("deferred", t.path("defer_loading").asBoolean());
        }
        ArrayNode messages = out.putArray("messages");
        int newest = -1;
        for (int i = 0; i < body.path("messages").size(); i++) {
            if ("user".equals(body.path("messages").get(i).path("role").asText())) newest = i;
        }
        for (int i = 0; i < body.path("messages").size(); i++) {
            JsonNode m = body.path("messages").get(i);
            ObjectNode o = messages.addObject();
            o.put("role", m.path("role").asText());
            JsonNode content = m.path("content");
            var text = new StringBuilder();
            ArrayNode added = JSON.createArrayNode();
            if (content.isTextual()) text.append(content.asText());
            for (JsonNode block : content) {
                switch (block.path("type").asText()) {
                    case "text" -> text.append(block.path("text").asText());
                    case "tool_addition" -> added.add(block.path("tool").path("name").asText());
                    default -> fail("a block the recording has no place for: " + block);
                }
            }
            if (added.isEmpty()) o.set("text", lines(i == newest ? aside.newest(text.toString()) : text.toString()));
            else o.set("offers", added);
        }
        return out;
    }

    /**
     * A request body of OpenAI's or Ollama's shape, whole -- every setting, every message -- but
     * for what {@code aside} keeps, and the tools by name alone.
     */
    static JsonNode body(JsonNode sent, Aside aside) {
        ObjectNode body = sent.deepCopy();
        ArrayNode messages = (ArrayNode) body.path("messages");
        ObjectNode first = (ObjectNode) messages.get(0), last = (ObjectNode) messages.get(messages.size() - 1);
        assertEquals(List.of("system", "user"), List.of(first.path("role").asText(), last.path("role").asText()));
        first.put("content", aside.system(first.path("content").asText()));
        last.put("content", aside.newest(last.path("content").asText()));
        ArrayNode names = JSON.createArrayNode();
        body.path("tools").forEach(t -> names.add(t.path("function").path("name").asText()));
        body.set("tools", names);
        return readable(body);
    }

    /** What each stage of each provider's request held of the two texts kept aside, by the stage's name. */
    static final Map<String, Aside> ASIDE = new java.util.LinkedHashMap<>();

    static Aside aside(String stage) {
        var aside = new Aside();
        ASIDE.put(stage, aside);
        return aside;
    }

    /** Everything the three providers are sent for the task's last step, as the recording holds it. */
    static ObjectNode sentNow() throws Exception {
        ASIDE.clear();
        ObjectNode out = JSON.createObjectNode();

        Request forAnthropic = lastRequest("anthropic", CLOUD);
        Sent anthropic = throughTheGateway("anthropic", forAnthropic);
        ObjectNode a = out.putObject("anthropic");
        a.set("engine", messages(forAnthropic.messages(), aside("anthropic/engine")));
        a.set("handed", messages(anthropic.provider().messages, aside("anthropic/handed")));
        a.set("handedTools", tools(anthropic.provider().config.tools()));
        a.set("ledger", ledger(anthropic.row(), anthropic.provider().messages));
        a.set("read", read(RequestBodies.anthropic(anthropic.provider().messages, anthropic.provider().config,
                "claude-opus-5-5"), aside("anthropic/read")));

        Request forOpenai = lastRequest("openai", CLOUD);
        Sent openai = throughTheGateway("openai", forOpenai);
        ObjectNode o = out.putObject("openai");
        o.set("engine", messages(forOpenai.messages(), aside("openai/engine")));
        o.set("ledger", ledger(openai.row(), openai.provider().messages));
        o.set("body", body(RequestBodies.openai(openai.provider().messages, openai.provider().config, "gpt-5"),
                aside("openai/body")));

        Request forTheLocalModel = lastRequest("ollama", LOCAL);
        ObjectNode l = out.putObject("ollama");
        l.set("engine", messages(forTheLocalModel.messages(), aside("ollama/engine")));
        l.set("body", body(RequestBodies.ollama(forTheLocalModel.messages(), forTheLocalModel.config()),
                aside("ollama/body")));
        return out;
    }

    /** Where two trees first differ, as a path and the two values: what a failure says before the whole of both. */
    static String firstDifference(JsonNode was, JsonNode is, String at) {
        if (was.equals(is)) return null;
        if (was.isObject() && is.isObject()) {
            var names = new java.util.TreeSet<String>();
            was.fieldNames().forEachRemaining(names::add);
            is.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (!was.has(name) || !is.has(name)) return at + "/" + name + ": " + (was.has(name) ? "gone" : "new");
                String d = firstDifference(was.get(name), is.get(name), at + "/" + name);
                if (d != null) return d;
            }
        }
        if (was.isArray() && is.isArray()) {
            for (int i = 0; i < Math.min(was.size(), is.size()); i++) {
                String d = firstDifference(was.get(i), is.get(i), at + "/" + i);
                if (d != null) return d;
            }
            return at + ": " + was.size() + " elements recorded, " + is.size() + " now";
        }
        return at + ": recorded " + was + ", now " + is;
    }

    @Test
    @DisplayName("a seven-step task's last think request is, for Anthropic's gateway and model, for OpenAI and for the local model, the bytes recorded before the newest result became a block of its own")
    void sentAsRecorded() throws Exception {
        ObjectNode now = sentNow();
        if (Boolean.getBoolean("golden.record")) {
            Path file = Path.of("src/test/resources").resolve(RECORDING);
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.writeValueAsString(now) + "\n", StandardCharsets.UTF_8);
        }
        JsonNode recorded;
        try (var in = ThinkRequestGoldenTest.class.getClassLoader().getResourceAsStream(RECORDING)) {
            assertNotNull(in, "the recording is missing: " + RECORDING);
            recorded = JSON.readTree(in);
        }

        // The premise: the newest message of the recorded request holds what the change could get wrong.
        JsonNode messages = recorded.path("anthropic").path("handed");
        String newest = text(messages.get(messages.size() - 1).path("content"));
        assertTrue(newest.contains("markers:" + LlmMessage.CACHE_BOUNDARY + "and" + LlmMessage.HISTORY_BLOCK + "and"
                        + LlmMessage.HISTORY_END + "and" + LlmMessage.image(0) + "and a lone \u0001."),
                "a result that quotes the prompt's markers reaches the provider as it is: " + newest);
        assertTrue(newest.contains("<email_1>") && newest.contains("<ssid_1>") && newest.contains("«secret removed»")
                        && newest.contains("«vault:ROUTER_PASSWORD»"),
                "the gateway rewrote that result, so a place in the message is not that place in what is sent: " + newest);
        assertTrue(newest.endsWith("\n\nMessage from the user while you were working:\nthe guest network too, please"
                        + THE_STEP + "\n"),
                "what the loop said after the result, then what changes on every step: " + newest);
        for (JsonNode cloud : List.of(now.path("anthropic").path("handed"), now.path("anthropic").path("read"),
                now.path("openai").path("body"))) {
            String sent = text(cloud);
            assertFalse(sent.contains(ROUTER_PASSWORD) || sent.contains("jan@example.org") || sent.contains("Example-Home"),
                    "and nothing it removes is in a cloud request: " + sent);
        }

        // The two texts the recording does not hold: every stage of a provider's request holds the
        // ones the engine wrote for it, whole.
        for (var stage : ASIDE.entrySet()) {
            Aside engine = ASIDE.get(stage.getKey().substring(0, stage.getKey().indexOf('/')) + "/engine");
            assertTrue(engine.systemPrompt.length() > 1_000 && engine.step.startsWith(STEP_OPENS + "- Platform: "),
                    "the premise: the engine wrote both for " + stage.getKey());
            assertEquals(engine.systemPrompt, stage.getValue().systemPrompt, stage.getKey() + ": the system prompt");
            assertEquals(engine.step, stage.getValue().step, stage.getKey() + ": the per-step block");
        }

        for (String provider : List.of("anthropic", "openai", "ollama")) {
            JsonNode was = recorded.path(provider), is = now.path(provider);
            assertEquals(was, is, provider + " is sent something else than was recorded, first at "
                    + firstDifference(was, is, provider));
        }
        // Mutation: take the markers' control character out of the result on its way into the
        // newest message (LlmMessage#unmarked) -> anthropic/engine/11/content differs, and what the
        // gateway hands on and the model reads with it. Send the per-step block of the newest
        // message as a message of its own -> anthropic/engine has a message more. Count what the
        // gateway's second reading of the newest result finds -> anthropic/ledger counts 10
        // identifiers replaced and 4 secrets removed, where 7 and 2 were.
    }
}
