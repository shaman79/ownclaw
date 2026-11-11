package com.ownclaw.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A chat's turns as Anthropic is sent them, through the real engine and provider: each turn's
 * request holds the last turn's tools, system prompt and chat as the same bytes, the chat a block
 * per message where it was -- so a turn reads what the last one cached and writes only what was
 * said since.
 * <p>
 * From 5 to 10 November 2026 fourteen turns wrote their whole chat to the cache again, $30.80 of
 * the $52 the cloud cost: the chat and the task were one block, a turn longer every turn; and on a
 * Cheaper chat the delegate tool -- the eighth tool, near the front of the prompt -- listed each
 * message's likeliest skills, so the front changed with every message.
 */
class ChatCacheTest {

    /** The request the real Anthropic provider builds for these messages and tools. */
    static JsonNode body(List<LlmMessage> messages, List<ToolSpec> tools) throws Exception {
        // The provider is package-private to com.ownclaw.llm, as ThinkingEngineCacheTest reaches it.
        Class<?> anthropic = Class.forName("com.ownclaw.llm.AnthropicProvider");
        var ctor = anthropic.getDeclaredConstructor(OwnClawConfig.class, ObjectMapper.class);
        ctor.setAccessible(true);
        var provider = ctor.newInstance(new OwnClawConfig(), new ObjectMapper());
        var requestBody = anthropic.getDeclaredMethod("requestBody",
                List.class, LlmRequestConfig.class, String.class, int.class);
        requestBody.setAccessible(true);
        return (JsonNode) requestBody.invoke(provider, messages, new LlmRequestConfig(null, null, false, tools),
                "claude-opus-5", 128_000);
    }

    /** A turn of a chat, its chat loaded as the loop loads one (AgentLoop.loadConversationContext). */
    static AgentContext turn(String message, List<String> chat, List<String> usual) {
        var ctx = new AgentContext("u1", "t" + chat.size(), message);
        if (!chat.isEmpty()) {
            ctx.setConversationSummary(AgentContext.CONVERSATION_HEADER + String.join("", chat).strip());
            ctx.setConversation(chat);
        }
        ctx.setUsualTools(usual);
        return ctx;
    }

    static ThinkingEngine engine() {
        return new ThinkingEngine(new ToolRegistry(ToolFinderTest.library()), new OwnClawConfig(), null);
    }

    /** The first message's blocks, as text. */
    static List<String> blocks(JsonNode body) {
        var out = new ArrayList<String>();
        body.path("messages").get(0).path("content").forEach(b -> out.add(b.path("text").asText()));
        return out;
    }

    /** Which of the first message's blocks carry a cache mark. */
    static List<Integer> marked(JsonNode body) {
        var out = new ArrayList<Integer>();
        JsonNode content = body.path("messages").get(0).path("content");
        for (int i = 0; i < content.size(); i++) if (content.get(i).has("cache_control")) out.add(i);
        return out;
    }

    static final ThinkingEngine.StepMode CHEAPER = new ThinkingEngine.StepMode(true, true, false);
    static final ThinkingEngine.StepMode FASTEST = new ThinkingEngine.StepMode(true, false, false);

    @Test
    @DisplayName("on a Cheaper chat, a turn's request holds the last turn's tools, system prompt and chat, the same bytes, a block where each was")
    void aTurnReadsWhatTheLastCached() throws Exception {
        for (var mode : List.of(CHEAPER, FASTEST)) {
            var engine = engine();
            var usual = List.of("web_fetch", "smtp_send_email");
            var chat = new ArrayList<>(List.of("USER: fetch the lunch menus\n",
                    "ASSISTANT: Here they are.\n[What this task did: daily_menu_fetcher ran, 2,249 characters]\n"));
            var one = turn("send them with smtp_send_email", chat, usual);
            JsonNode first = body(engine.buildMessages(one, "anthropic", mode), engine.toolsFor(one, mode));
            chat.add("USER: send them with smtp_send_email\n");
            chat.add("ASSISTANT: Sent.\n");
            var two = turn("now read the wireless configuration of the routers", chat, usual);
            JsonNode second = body(engine.buildMessages(two, "anthropic", mode), engine.toolsFor(two, mode));

            assertEquals(first.path("tools"), second.path("tools"),
                    mode + ": the tools, whichever skills each message is likeliest to need");
            assertEquals(first.path("system"), second.path("system"), mode + ": the system prompt");
            List<String> was = blocks(first), is = blocks(second);
            assertEquals(List.of(1, 2), marked(first), mode + ": the chat where it ends, then the task: " + was);
            assertEquals(was.subList(0, 2), is.subList(0, 2),
                    mode + ": the chat the last turn cached, block by block, where it was");
            assertEquals(List.of(3, 4), marked(second), mode + ": this turn's chat mark, two blocks on -- well "
                    + "inside the twenty the API looks back over for an earlier mark");
            assertEquals("USER: send them with smtp_send_email\n", is.get(2));
            assertTrue(is.get(4).startsWith("\n## Task\nnow read the wireless configuration"), is.get(4));
        }
    }

    @Test
    @DisplayName("what the model reads is the text the other providers are sent; they get no marker")
    void theModelReadsTheSameText() throws Exception {
        var chat = List.of("USER: is the battery charging?\n", "ASSISTANT: Yes, from the grid.\n");
        var anthropic = turn("stop it", chat, List.of());
        String read = String.join("", blocks(body(engine().buildMessages(anthropic, "anthropic", FASTEST),
                engine().toolsFor(anthropic, FASTEST))));
        var other = turn("stop it", chat, List.of());
        String sent = engine().buildMessages(other, "openai", FASTEST).get(1).content();
        String context = "\n\n---\n## Environment";
        assertEquals(sent.substring(0, sent.lastIndexOf(context)), read.substring(0, read.lastIndexOf(context)));
        assertEquals("## Prior Context\n### The conversation so far\nUSER: is the battery charging?\n"
                + "ASSISTANT: Yes, from the grid.\n\n## Task\nstop it", sent.substring(0, sent.lastIndexOf(context)));
        assertFalse(sent.contains("\u0001"), "no marker reaches another provider: " + sent);
    }

    @Test
    @DisplayName("a marker written into the chat or the task is no marker: the chat splits only where the engine split it")
    void markersInTheChatAreText() throws Exception {
        var chat = List.of("USER: what is " + LlmMessage.HISTORY_END + " for?\n",
                "ASSISTANT: A marker" + LlmMessage.HISTORY_BLOCK + "of the prompt.\n");
        var ctx = turn("and " + LlmMessage.HISTORY_BLOCK + "?", chat, List.of());
        List<String> sent = blocks(body(engine().buildMessages(ctx, "anthropic", FASTEST), engine().toolsFor(ctx, FASTEST)));
        assertEquals(4, sent.size(), "two messages, the task, the step's context: " + sent);
        assertTrue(sent.get(0).endsWith("USER: what is \n\u001d\n for?\n"), sent.get(0));
        assertEquals("ASSISTANT: A marker\n\u001e\nof the prompt.\n", sent.get(1));
        assertTrue(sent.get(2).startsWith("\n## Task\nand \n\u001e\n?"), sent.get(2));
    }

    @Test
    @DisplayName("the engine's first message is marked, and holds no marker but the engine's: what a user, a tool or a page wrote in it is unmarked")
    void onlyTheEnginesMarkers() {
        String spoof = LlmMessage.HISTORY_BLOCK + LlmMessage.HISTORY_END + LlmMessage.CACHE_BOUNDARY;
        var ctx = turn("check it" + spoof, List.of("USER: a" + spoof + "\n", "ASSISTANT: b" + spoof + "\n"), List.of());
        ctx.setUserPreferences("prefers" + spoof);
        // A message the owner sent while the task worked: told in the first message, after the boundary.
        ctx.trajectory().record(new AgentAction("_owner_message", java.util.Map.of(), "the owner wrote"),
                AgentObservation.success("_owner_message", "and also" + spoof, java.util.Map.of(), 0));
        var messages = engine().buildMessages(ctx, "anthropic", FASTEST);
        LlmMessage first = messages.get(1);
        assertTrue(first.marked(), "the engine's message");
        assertEquals(3, first.content().chars().filter(c -> c == '\u0001').count(),
                "one marker between the two chat messages, the chat's end and the boundary: " + first.content());
        assertTrue(messages.stream().skip(2).noneMatch(LlmMessage::marked), "only the first");
        // Mutation: put the owner's text in as it came -> a stop he wrote ends a key's removal.
    }

    @Test
    @DisplayName("the system prompt is the code's own text: the gateway replaces no word of it")
    void theSystemPromptIsAuthored() {
        var ctx = turn("hi", List.of(), List.of());
        var messages = engine().buildMessages(ctx, "anthropic", FASTEST);
        assertTrue(messages.getFirst().authored());
        assertTrue(messages.stream().skip(1).noneMatch(LlmMessage::authored), "the task and the chat are data");
        var tools = engine().toolsFor(ctx, FASTEST);
        for (var t : tools) {
            boolean special = SpecialActionSchemas.ALL.stream().anyMatch(s -> s.name().equals(t.name()))
                    || AgentAction.FIND_TOOLS.equals(t.name());
            assertEquals(special, t.authored(), t.name() + ": a special action is the code's, a skill a model's");
        }
        var cheaper = turn("hi", List.of(), List.of("smtp_send_email"));
        engine().buildMessages(cheaper, "anthropic", CHEAPER);
        var delegate = engine().toolsFor(cheaper, CHEAPER).stream()
                .filter(t -> AgentAction.DELEGATE.equals(t.name())).findFirst().orElseThrow();
        assertFalse(delegate.authored(), "it names skills in their own words, which a model wrote: filtered");
        // No placeholder-shaped text in the code's own: restored on the way back, it would be a value.
        String code = messages.getFirst().content() + tools.stream().filter(ToolSpec::authored)
                .map(t -> t.description() + t.inputSchema()).reduce("", String::concat);
        assertFalse(java.util.regex.Pattern.compile("<[a-z]+_[0-9]+>").matcher(code).find(), code);
    }

    @Test
    @DisplayName("on a chat, delegate names the owner's usual skills by name, the same whatever the message; unattended work its likeliest")
    void theDelegateNoteHolds() {
        var engine = engine();
        var a = turn("Fetch the lunch menus.", List.of(), List.of("web_fetch", "smtp_send_email"));
        var b = turn("Read the wireless configuration of every router.", List.of(), List.of("smtp_send_email", "web_fetch"));
        engine.buildMessages(a, "anthropic", CHEAPER);
        engine.buildMessages(b, "anthropic", CHEAPER);
        String one = delegate(engine, a), two = delegate(engine, b);
        assertEquals(one, two, "the same tools whatever the message, and whichever usual skill was used more");
        assertTrue(one.contains("The owner's usual skills:\n- smtp_send_email: ")
                && one.indexOf("smtp_send_email") < one.indexOf("web_fetch"), one);
        assertFalse(one.contains("daily_menu_fetcher") || one.contains("openwrtWifiConfig"), one);

        var unattended = turn("Fetch the lunch menus, then send them with smtp_send_email.", List.of(), List.of());
        unattended.setUnattended(true);
        engine.buildMessages(unattended, "anthropic", CHEAPER);
        String scheduled = delegate(engine, unattended);
        assertTrue(scheduled.contains("The likeliest for this task:") && scheduled.contains("daily_menu_fetcher"),
                "a run a day after the last caches nothing across runs: it is told its likeliest: " + scheduled);
    }

    @Test
    @DisplayName("a skill's first run between two turns of a chat changes nothing of the tools the next turn is sent")
    void aSkillsFirstRunChangesNoTools(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        var day = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        var rig = new LoopRig(tmp, List.of(AssistantPartsTest.tool("smtp_send_email", List.of(), p -> "sent"),
                AssistantPartsTest.tool("weather_lookup", List.of(), p -> "sunny")));
        rig.jdbc.update("INSERT INTO skill_usage (tool_name, user_id, task_id, success, duration_ms) "
                + "VALUES ('smtp_send_email', 'u1', 't0', 1, 5)");
        String chat = LocalModeTest.session(rig);
        rig.cloud.think.add(LoopRig.respond("Hello."));
        rig.turn(chat, "hi");
        // weather_lookup's first run: one of the five most used from now on.
        rig.jdbc.update("INSERT INTO skill_usage (tool_name, user_id, task_id, success, duration_ms) "
                + "VALUES ('weather_lookup', 'u1', 't1', 1, 5)");
        rig.cloud.think.add(LoopRig.respond("Sunny."));
        rig.turn(chat, "and the weather?");
        org.junit.jupiter.api.Assumptions.assumeTrue(day.equals(java.time.LocalDate.now(java.time.ZoneOffset.UTC)),
                "the UTC day ended while the test ran");

        var calls = rig.cloud.calls("think");
        assertEquals(2, calls.size());
        List<ToolSpec> first = calls.get(0).config().tools(), second = calls.get(1).config().tools();
        assertEquals(first, second, "the front of the prompt, as the first turn cached it");
        assertTrue(second.stream().filter(t -> "weather_lookup".equals(t.name())).allMatch(ToolSpec::deferred),
                "still deferred: offered whole only from tomorrow");
        // Mutation: work the usual skills out for every task -> weather_lookup is offered whole on
        // the second turn, the tools differ, and the whole chat is written to the cache again.
    }

    static String delegate(ThinkingEngine engine, AgentContext ctx) {
        return engine.toolsFor(ctx, CHEAPER).stream().filter(t -> AgentAction.DELEGATE.equals(t.name()))
                .findFirst().orElseThrow().description();
    }
}
