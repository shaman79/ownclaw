package com.ownclaw.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the Anthropic request looks like to the prompt cache: the task is cached on a task's first
 * call and read back on the next; the chat it came from is a block per message, cached where it
 * ends; the stable prefix -- tools, system prompt, chat, task -- is cached for an hour and the steps
 * after it for five minutes, up to the newest result, the hour-long marks first -- for an hour too
 * once the task's steps have been further apart than five minutes; and no request carries more
 * cache marks than the API allows.
 */
class AnthropicCacheTest {

    static final String MARK = LlmMessage.CACHE_BOUNDARY;
    /** What changes on every step, as the engine ends a think call's newest message with it. */
    static final String STEP = "\n\n---\n## Environment\n- DateTime: now";
    private final AnthropicProvider provider = new AnthropicProvider(new OwnClawConfig(), new ObjectMapper());

    private JsonNode body(List<LlmMessage> messages, boolean tools) {
        return body(messages, tools, false);
    }

    /** @param farApart the task's steps have been further apart than a five-minute cache entry lives */
    private JsonNode body(List<LlmMessage> messages, boolean tools, boolean farApart) {
        var specs = tools ? List.of(new ToolSpec("respond", "answer", Map.of("type", "object"))) : null;
        return provider.requestBody(messages, new LlmRequestConfig(null, null, false, specs).withHourLongSteps(farApart),
                "claude-opus-5", 128_000);
    }

    private static String text(JsonNode content) {
        return content.isArray() ? content.get(0).path("text").asText() : content.asText();
    }

    @Test
    @DisplayName("step 0: the task is its own cached block; step 1 sends the same bytes as the whole first message")
    void theTaskIsCachedAndReadBack() {
        String task = "## Prior Context\n" + "a long chat ".repeat(500) + "\n\n## Task\nsummarise it";
        var step0 = body(List.of(LlmMessage.system("SYSTEM"),
                LlmMessage.marked(task + MARK + "---\n## Environment\n- DateTime: now")), true);
        JsonNode first = step0.path("messages").get(0).path("content");
        assertTrue(first.isArray(), "two blocks: " + first);
        assertEquals(task, first.get(0).path("text").asText());
        assertEquals("ephemeral", first.get(0).path("cache_control").path("type").asText());
        assertTrue(first.get(1).path("text").asText().startsWith("---\n## Environment")
                || first.get(1).path("text").asText().startsWith("\n\n---\n## Environment"));
        assertTrue(first.get(1).path("cache_control").isMissingNode(), "what changes each step is not cached");
        assertFalse(step0.toString().contains("\\u0001"), "the marker is never sent: " + step0);

        var step1 = body(List.of(LlmMessage.system("SYSTEM"), LlmMessage.marked(task + MARK),
                LlmMessage.assistant("{\"tool\":\"delegate\"}"), LlmMessage.user("result\n\n---\n## Environment")), true);
        assertEquals(task, text(step1.path("messages").get(0).path("content")),
                "byte for byte the cached block, so the cache is read instead of written again");
        assertEquals(1, step1.path("messages").get(0).path("content").size(), "and nothing after it in that message");
    }

    /** Every cache mark of a request, in the order the API reads the prompt: tools, system, messages. */
    private static List<JsonNode> marks(JsonNode body) {
        var marks = new ArrayList<JsonNode>();
        for (JsonNode t : body.path("tools")) if (t.has("cache_control")) marks.add(t.path("cache_control"));
        for (JsonNode b : body.path("system")) if (b.has("cache_control")) marks.add(b.path("cache_control"));
        for (JsonNode m : body.path("messages")) {
            for (JsonNode b : m.path("content")) if (b.has("cache_control")) marks.add(b.path("cache_control"));
        }
        return marks;
    }

    private static final String HOUR = "{\"type\":\"ephemeral\",\"ttl\":\"1h\"}";
    private static final String FIVE_MINUTES = "{\"type\":\"ephemeral\"}";

    @Test
    @DisplayName("a think call's stable prefix -- tools, system prompt, task -- is cached for an hour; the steps slide on five minutes, after it, up to the newest result")
    void theStablePrefixIsCachedForAnHour() {
        var step3 = body(List.of(LlmMessage.system("SYSTEM"), LlmMessage.marked("the task" + MARK),
                LlmMessage.assistant("action 1"), LlmMessage.user("result 1"),
                LlmMessage.assistant("action 2"), LlmMessage.newest("result 2", STEP)), true);
        assertEquals(List.of(HOUR, HOUR, FIVE_MINUTES), marks(step3).stream().map(JsonNode::toString).toList(),
                "the system prompt -- with the tools before it -- and the task for an hour, then the one "
                        + "sliding mark; the API takes the longer-lived ones first: " + step3);
        assertTrue(step3.path("tools").get(0).path("cache_control").isMissingNode(),
                "the tools have no mark of their own: the system prompt's caches them");
        var messages = step3.path("messages");
        // The mark stood on the message before the newest, and the newest went as one text, paid
        // in full: its result is now a block of its own, and the mark is on it.
        JsonNode newest = messages.get(4).path("content");
        assertEquals(2, newest.size(), "the result, then what changes every step: " + newest);
        assertEquals("result 2", newest.get(0).path("text").asText());
        assertEquals(FIVE_MINUTES, newest.get(0).path("cache_control").toString(), "on the newest result");
        assertEquals(STEP, newest.get(1).path("text").asText());
        assertTrue(newest.get(1).path("cache_control").isMissingNode(), "what changes every step is not cached");
        assertTrue(messages.get(3).path("content").isTextual(), "no mark on the message before it any more");

        var step0 = body(List.of(LlmMessage.system("SYSTEM"), LlmMessage.marked("the task" + MARK + STEP)), true);
        assertEquals(List.of(HOUR, HOUR), marks(step0).stream().map(JsonNode::toString).toList(), step0.toString());
        // Mutation: send the hour on the sliding mark too -> a two-minute step pays 2x on what
        // the next one already reads; send it last -> the API refuses the order. Mark the message
        // before the newest again -> messages[3] is a block, and the result is paid in full.
    }

    /**
     * A think call's messages on a task's step {@code n}, from 1, as the engine writes them: the
     * task -- after {@code chat}, when there is one -- the steps before, and the newest result with
     * what changes every step after it.
     */
    static List<LlmMessage> step(int n, List<String> chat, java.util.function.IntFunction<String> result) {
        String task = chat == null ? "the task" : chatThenTask(chat, "the task");
        var messages = new ArrayList<LlmMessage>();
        messages.add(LlmMessage.system("SYSTEM"));
        messages.add(LlmMessage.marked(task + MARK + (n == 1 ? STEP : "")));
        for (int i = 1; i < n; i++) {
            messages.add(LlmMessage.assistant("action " + i));
            messages.add(i == n - 1 ? LlmMessage.newest(result.apply(i), STEP) : LlmMessage.user(result.apply(i)));
        }
        return messages;
    }

    /** Every cache mark of the messages, as the message it is on, the block, and how long it lives. */
    private static List<String> where(JsonNode body) {
        var out = new ArrayList<String>();
        JsonNode messages = body.path("messages");
        for (int m = 0; m < messages.size(); m++) {
            JsonNode content = messages.get(m).path("content");
            for (int b = 0; b < content.size(); b++) {
                if (content.get(b).has("cache_control")) {
                    out.add(m + "/" + b + " " + (HOUR.equals(content.get(b).path("cache_control").toString()) ? "1h" : "5m"));
                }
            }
        }
        return out;
    }

    @Test
    @DisplayName("step 1 has no sliding mark; from step 2 on it is on the first block of the newest message, the result, for five minutes -- and no request has more than four marks")
    void theSlidingMarkIsOnTheNewestResult() {
        List<String> chat = List.of("USER: is the battery charging?\n", "ASSISTANT: Yes, from the grid.\n");
        for (int n : List.of(1, 2, 3, 4, 12, 40)) {
            JsonNode plain = body(step(n, null, i -> "result " + i), true);
            JsonNode afterAChat = body(step(n, chat, i -> "result " + i), true);
            int newest = 2 * (n - 1);
            assertEquals(n == 1 ? List.of("0/0 1h") : List.of("0/0 1h", newest + "/0 5m"), where(plain), "step " + n);
            assertEquals(n == 1 ? List.of("0/1 1h", "0/2 1h") : List.of("0/1 1h", "0/2 1h", newest + "/0 5m"),
                    where(afterAChat), "step " + n + " of a chat's turn: the chat's end, the task, the newest result");
            assertEquals(n == 1 ? 2 : 3, marks(plain).size(), "with the system prompt's");
            assertEquals(n == 1 ? 3 : 4, marks(afterAChat).size(), "four at most, which the API takes");
            if (n == 1) continue;
            JsonNode message = plain.path("messages").get(newest).path("content");
            assertEquals(List.of("result " + (n - 1), STEP),
                    List.of(message.get(0).path("text").asText(), message.get(1).path("text").asText()),
                    "the result, then what changes every step, and nothing of either lost");
            for (int m = 1; m < newest; m++) {
                assertTrue(plain.path("messages").get(m).path("content").isTextual(), "step " + n + ": message " + m
                        + " is text, as it was cached");
            }
        }
        // Mutation: mark the message before the newest user turn, as before -> where() names an
        // odd message, an action. Mark the first step's context too -> step 1 has a third mark.
    }

    @Test
    @DisplayName("the block a step marks is, on the next step, the whole of that message, byte for byte -- whatever bytes the result holds")
    void theNextStepSendsTheMarkedBlockAgain() {
        // What a tool can print: the prompt's own markers, their control character alone, the
        // line the per-step block begins with, blanks at either end.
        java.util.function.IntFunction<String> result = i -> switch (i % 5) {
            case 0 -> "a page" + MARK + "quoting" + LlmMessage.HISTORY_BLOCK + "the" + LlmMessage.HISTORY_END + "markers";
            case 1 -> "an image's" + LlmMessage.image(0) + "and a lone \u0001 " + i;
            case 2 -> "a result that reads like the end of one" + STEP;
            case 3 -> "\n  blanks around it " + i + " \n\n";
            default -> "result " + i;
        };
        for (int n = 2; n < 14; n++) {
            JsonNode now = body(step(n, null, result), true), next = body(step(n + 1, null, result), true);
            int at = 2 * (n - 1);
            JsonNode marked = now.path("messages").get(at).path("content").get(0);
            assertTrue(marked.has("cache_control"), "the premise: step " + n + " marks its newest result");
            assertEquals(result.apply(n - 1), marked.path("text").asText(), "the result as the tool printed it");
            JsonNode later = next.path("messages").get(at).path("content");
            assertTrue(later.isTextual(), "one text, which is one block: " + later);
            assertEquals(marked.path("text").asText(), later.asText(), "step " + (n + 1) + " sends step " + n + "'s block again");
            for (int m = 0; m < at; m++) {
                assertEquals(unmarked(now.path("messages").get(m)), unmarked(next.path("messages").get(m)),
                        "and every message before it as it was");
            }
        }
        // Mutation: take the markers out of the block the mark goes on (LlmMessage#plain), as the
        // first message's blocks are -> the block of a result that holds one is not the message
        // the next step sends.
    }

    /** A message without its cache marks, a text in one block the same as the text alone. */
    private static JsonNode unmarked(JsonNode message) {
        var copy = (com.fasterxml.jackson.databind.node.ObjectNode) message.deepCopy();
        JsonNode content = copy.path("content");
        if (content.isArray()) {
            content.forEach(block -> ((com.fasterxml.jackson.databind.node.ObjectNode) block).remove("cache_control"));
            if (content.size() == 1 && content.get(0).has("text")) copy.put("content", content.get(0).path("text").asText());
        }
        return copy;
    }

    @Test
    @DisplayName("a newest message that names no lasting beginning, or one of only blanks, is marked as before: on the message before it, and sent as the one text it is")
    void withoutABeginningTheMarkIsWhereItWas() {
        var before = List.of(LlmMessage.system("SYSTEM"), LlmMessage.marked("the task" + MARK),
                LlmMessage.assistant("action 1"), LlmMessage.user("result 1"), LlmMessage.assistant("action 2"));
        for (LlmMessage newest : List.of(LlmMessage.user("result 2" + STEP), LlmMessage.newest(" \n\t", STEP),
                LlmMessage.newest("result 2", " \n "))) {
            var messages = new ArrayList<>(before);
            messages.add(newest);
            JsonNode body = body(messages, true);
            assertEquals(List.of("0/0 1h", "3/0 5m"), where(body), "on the message before the newest user turn: " + body);
            assertEquals(newest.content(), body.path("messages").get(4).path("content").asText(),
                    "the newest as the text it is: a block of blanks is one the API refuses");
        }
        // A skill's code written in parts is no think call: its marks are where they were.
        var parts = body(List.of(LlmMessage.system("Write the skill."), LlmMessage.user("the specification"),
                LlmMessage.assistant("part 1"), LlmMessage.user("go on")), false);
        assertEquals(List.of("1/0 5m"), where(parts), parts.toString());

        // A beginning that is not how the text begins is none: the message drops it, whoever made it.
        LlmMessage notItsBeginning = new LlmMessage(LlmMessage.Role.USER, "result 2" + STEP, false, false, List.of(), "result 3");
        assertNull(notItsBeginning.lasting());
        assertNull(new LlmMessage(LlmMessage.Role.USER, "result 2" + STEP, false, false, List.of(), "").lasting());
        assertEquals("result 2", LlmMessage.newest("result 2", STEP).lasting(), "the premise: one that is, is kept");
        var misnamed = new ArrayList<>(before);
        misnamed.add(notItsBeginning);
        assertEquals(List.of("0/0 1h", "3/0 5m"), where(body(misnamed, true)));

        // All of a message its lasting beginning -- a key that never ends took what followed with
        // it (CloudGateway#lasting) -- is one block, marked: the next step sends it so.
        var whole = new ArrayList<>(before);
        whole.add(LlmMessage.newest("result 2«secret removed»", ""));
        JsonNode body = body(whole, true);
        assertEquals(List.of("0/0 1h", "4/0 5m"), where(body));
        assertEquals(1, body.path("messages").get(4).path("content").size(), "no empty second block");
        // Mutation: split whenever a beginning is named -> a block of blanks, and a mark on one.
        // Keep a beginning the content does not begin with (LlmMessage) -> it is not null.
    }

    @Test
    @DisplayName("once the task's steps have been far apart the sliding mark lives an hour like the marks before it; never an hour-long mark after a five-minute one")
    void farApartTheStepsAreCachedForAnHour() {
        List<String> chat = List.of("USER: a\n", "ASSISTANT: b\n");
        assertEquals(List.of("0/0 1h", "4/0 1h"), where(body(step(3, null, i -> "result " + i), true, true)));
        assertEquals(List.of(HOUR, HOUR, HOUR, HOUR),
                marks(body(step(3, chat, i -> "result " + i), true, true)).stream().map(JsonNode::toString).toList(),
                "all four for an hour: the system prompt, the chat, the task, the newest result");
        assertEquals(List.of("0/0 1h", "4/0 5m"), where(body(step(3, null, i -> "result " + i), true, false)),
                "the premise: five minutes while they follow one another");
        assertEquals(List.of("0/0 1h"), where(body(step(1, null, i -> ""), true, true)), "step 1 has none to lengthen");

        // Where the mark is put as before, it lives as long.
        assertEquals(List.of("0/0 1h", "3/0 1h"), where(body(List.of(LlmMessage.system("SYSTEM"),
                LlmMessage.marked("the task" + MARK), LlmMessage.assistant("action 1"), LlmMessage.user("result 1"),
                LlmMessage.assistant("action 2"), LlmMessage.user("result 2" + STEP)), true, true)));

        // A first message whose boundary the filter took: the system prompt is cached for five
        // minutes, and an hour-long mark after it is a request the API refuses.
        var noBoundary = body(List.of(LlmMessage.system("SYSTEM"), LlmMessage.marked("the task«secret removed»"),
                LlmMessage.assistant("action 1"), LlmMessage.newest("result 1", STEP)), true, true);
        assertEquals(List.of(FIVE_MINUTES, FIVE_MINUTES), marks(noBoundary).stream().map(JsonNode::toString).toList(),
                noBoundary.toString());
        // Nor is a request that is no think call given one, whatever it says of its steps.
        var codegen = body(List.of(LlmMessage.system("Write the skill."), LlmMessage.user("the specification"),
                LlmMessage.assistant("part 1"), LlmMessage.user("go on")), false, true);
        assertEquals(List.of(FIVE_MINUTES, FIVE_MINUTES), marks(codegen).stream().map(JsonNode::toString).toList());
        // Mutation: the hour whenever the request asks -> [5m, 1h] for the two above, which the
        // API answers with HTTP 400. Never the hour -> a task of long steps writes all of them
        // again after each.
    }

    /** A task's first message as the engine writes it for a chat: its messages, the end of the chat, the task. */
    static String chatThenTask(List<String> chat, String task) {
        return "## Prior Context\n### The conversation so far\n" + String.join(LlmMessage.HISTORY_BLOCK, chat)
                + LlmMessage.HISTORY_END + "\n## Task\n" + task;
    }

    @Test
    @DisplayName("the chat a task came from is a block per message, the last cached for an hour, before the task's own")
    void theChatIsABlockPerMessage() {
        List<String> chat = List.of("USER: is the battery charging?\n", "ASSISTANT: Yes, from the grid.\n");
        var step1 = body(List.of(LlmMessage.system("SYSTEM"), LlmMessage.marked(chatThenTask(chat, "stop it") + MARK),
                LlmMessage.assistant("action 1"), LlmMessage.user("result 1\n\n---\n## Environment")), true);
        JsonNode first = step1.path("messages").get(0).path("content");
        assertEquals(3, first.size(), "two messages and the task: " + first);
        assertEquals("## Prior Context\n### The conversation so far\nUSER: is the battery charging?\n",
                first.get(0).path("text").asText());
        assertEquals("ASSISTANT: Yes, from the grid.\n", first.get(1).path("text").asText());
        assertEquals("\n## Task\nstop it", first.get(2).path("text").asText());
        assertTrue(first.get(0).path("cache_control").isMissingNode(), "one mark for the chat, where it ends");
        assertEquals(HOUR, first.get(1).path("cache_control").toString());
        assertEquals(HOUR, first.get(2).path("cache_control").toString());
        assertEquals(List.of(HOUR, HOUR, HOUR, FIVE_MINUTES), marks(step1).stream().map(JsonNode::toString).toList(),
                "the system prompt, the chat and the task for an hour, the sliding mark after them: " + step1);
        assertFalse(step1.toString().contains("\u0001"), "no marker is sent: " + step1);
        assertEquals("## Prior Context\n### The conversation so far\nUSER: is the battery charging?\n"
                        + "ASSISTANT: Yes, from the grid.\n\n## Task\nstop it",
                first.get(0).path("text").asText() + first.get(1).path("text").asText() + first.get(2).path("text").asText(),
                "what the model reads is the text the other providers are sent");
    }

    @Test
    @DisplayName("the chat's next turn sends the last turn's blocks again, a block apart, so it reads what that one cached")
    void theNextTurnReadsTheChatBack() {
        var turn1 = body(List.of(LlmMessage.system("SYSTEM"), LlmMessage.marked(chatThenTask(
                List.of("USER: a\n", "ASSISTANT: b\n"), "c") + MARK + "\n\n---\n## Environment")), true);
        var turn2 = body(List.of(LlmMessage.system("SYSTEM"), LlmMessage.marked(chatThenTask(
                List.of("USER: a\n", "ASSISTANT: b\n", "USER: c\n", "ASSISTANT: d\n"), "e") + MARK
                + "\n\n---\n## Environment")), true);
        assertEquals(turn1.path("system"), turn2.path("system"));
        assertEquals(turn1.path("tools"), turn2.path("tools"));
        JsonNode one = turn1.path("messages").get(0).path("content");
        JsonNode two = turn2.path("messages").get(0).path("content");
        assertEquals(one.get(0), two.get(0), "the chat's first block, as it was");
        assertEquals(one.get(1).path("text"), two.get(1).path("text"),
                "the block the last turn cached the chat up to, the same bytes, a boundary where it was");
        assertEquals(HOUR, two.get(3).path("cache_control").toString(),
                "this turn's mark, two blocks on: well inside the twenty the API looks back over");
    }

    @Test
    @DisplayName("a request without the engine's marker -- code generation, a summary -- caches for five minutes only")
    void otherRequestsCacheForFiveMinutes() {
        var codegen = body(List.of(LlmMessage.system("Write the skill."), LlmMessage.user("a scanner")), false);
        assertEquals(List.of(FIVE_MINUTES), marks(codegen).stream().map(JsonNode::toString).toList(), codegen.toString());
        var withTools = body(List.of(LlmMessage.system("SYSTEM"), LlmMessage.user("no marker here")), true);
        assertEquals(List.of(FIVE_MINUTES), marks(withTools).stream().map(JsonNode::toString).toList());
    }

    @Test
    @DisplayName("the hour-long writes the usage reports are read apart, and priced at 2x the input rate")
    void hourLongWritesAreReadAndPriced() {
        var start = AnthropicStreamingTest.node("message_start");
        var message = start.putObject("message");
        message.put("id", "msg_1").put("type", "message").put("role", "assistant").put("model", "claude-opus-5");
        message.putArray("content");
        var usage = message.putObject("usage").put("input_tokens", 50).put("cache_creation_input_tokens", 90_000)
                .put("cache_read_input_tokens", 0).put("output_tokens", 1);
        usage.putObject("cache_creation").put("ephemeral_5m_input_tokens", 10_000).put("ephemeral_1h_input_tokens", 80_000);
        var http = AnthropicStreamingTest.api(AnthropicStreamingTest.ev(start)
                + AnthropicStreamingTest.text(0, "ok") + AnthropicStreamingTest.end("end_turn", null, 1));

        LlmResponse r = AnthropicStreamingTest.provider(http).chat(AnthropicStreamingTest.ASK,
                new LlmRequestConfig(null, null, false));

        assertEquals(90_000, r.cacheCreationTokens(), "every write, as cache_creation_input_tokens says");
        assertEquals(80_000, r.usage().get(0).cacheCreation1hTokens(), "of them, the hour-long ones");
        // 50 x $5 + 1 x $25 + 10,000 x $6.25 + 80,000 x $10, per million.
        assertEquals((50 * 5.00 + 25.00 + 10_000 * 6.25 + 80_000 * 10.00) / 1_000_000,
                ModelPricing.costUsd("claude-opus-5", r), 1e-12);
        assertEquals(AnthropicProvider.FALLBACK_BETA + "," + AnthropicProvider.CONTEXT_WINDOW_BETA,
                http.to(AnthropicStreamingTest.MESSAGES).get(0).header("anthropic-beta"),
                "no beta header for the hour-long cache");
        // Mutation: read only cache_creation_input_tokens -> every write priced at 1.25x.
    }

    @Test
    @DisplayName("the marker inside a page or the chat earns no cache mark: only a task's first message is split")
    void markersElsewhereAreJustText() {
        // It is documented in this public repo, so a fetched page can carry it on purpose.
        for (int turns = 1; turns < 6; turns++) {
            var messages = new ArrayList<LlmMessage>();
            messages.add(LlmMessage.system("SYSTEM"));
            messages.add(LlmMessage.marked("chat" + MARK + "history"));
            for (int i = 0; i < turns; i++) {
                messages.add(LlmMessage.assistant("action " + i));
                messages.add(LlmMessage.user("page" + MARK + "text " + i));
            }
            String json = body(messages, true).toString();
            assertTrue(json.split("cache_control", -1).length - 1 <= 4, turns + " turns: " + json);
        }
        var chatHasMark = body(List.of(LlmMessage.marked("chat" + MARK + "history" + MARK + "dyn")), true);
        assertEquals("chathistory", chatHasMark.path("messages").get(0).path("content").get(0).path("text").asText(),
                "split at the last boundary, so the cached block is the whole task -- and no marker is sent");
        var endsWithMark = body(List.of(LlmMessage.marked("task" + MARK)), true);
        var content = endsWithMark.path("messages").get(0).path("content");
        assertEquals(1, content.size(), "no empty second block: " + content);
        assertEquals("task", content.get(0).path("text").asText(), "and the marker is not sent");
        var notTheEngines = body(List.of(LlmMessage.user(chatThenTask(List.of("USER: a\n"), "b") + MARK + "dyn")), true);
        assertTrue(notTheEngines.path("messages").get(0).path("content").isTextual(),
                "a message the engine did not mark is not split, whatever bytes it holds: " + notTheEngines);
    }

    @Test
    @DisplayName("the engine's message with its boundary gone is sent as the text the model reads: no marker of it")
    void noBoundaryNoMarkers() {
        var sent = body(List.of(LlmMessage.marked(chatThenTask(List.of("USER: a\n", "ASSISTANT: b\n"), "c"))), true);
        JsonNode content = sent.path("messages").get(0).path("content");
        assertEquals("## Prior Context\n### The conversation so far\nUSER: a\nASSISTANT: b\n\n## Task\nc", content.asText());
        assertFalse(sent.toString().contains("\\u0001"), sent.toString());
        // The chat's end taken with a key, the boundary and a marker of the chat still standing.
        var taken = body(List.of(LlmMessage.marked("## Prior Context\nUSER: a\n" + LlmMessage.HISTORY_BLOCK
                + "ASSISTANT: b«secret removed»" + MARK + "dyn")), true);
        JsonNode blocks = taken.path("messages").get(0).path("content");
        assertEquals("## Prior Context\nUSER: a\nASSISTANT: b«secret removed»", blocks.get(0).path("text").asText(),
                "one block for chat and task alike, the marker left in it taken out");
        assertFalse(taken.toString().toLowerCase(java.util.Locale.ROOT).matches("(?s).*\\\\u00(01|1c|1d|1e).*"),
                taken.toString());
        // Mutation: send it as it is -> the chat's markers reach Anthropic.
    }

    @Test
    @DisplayName("never more than four cache marks, whatever the length of the chat and of the task so far")
    void atMostFourMarks() {
        String page = "a page" + LlmMessage.HISTORY_BLOCK + "with" + LlmMessage.HISTORY_END + "markers" + MARK;
        for (int chat : List.of(-1, 0, 1, 2, 30)) {
            var history = new ArrayList<String>();
            for (int i = 0; i < chat; i++) history.add((i % 2 == 0 ? "USER: " : "ASSISTANT: ") + i + "\n");
            for (boolean split : List.of(true, false)) {
                for (int turns = 0; turns < 8; turns++) {
                    // The newest message as the engine writes it, its result named, and as one text;
                    // the steps close together, and far apart.
                    for (int shape = 0; shape < 4; shape++) {
                        boolean named = shape % 2 == 0, farApart = shape >= 2;
                        var messages = new ArrayList<LlmMessage>();
                        messages.add(LlmMessage.system("SYSTEM" + MARK + "dynamic"));
                        String task = chat < 0 ? "task" : chatThenTask(history, "task");
                        messages.add(LlmMessage.marked(split ? task + MARK + (turns == 0 ? "dyn" : "") : task));
                        for (int i = 0; i < turns; i++) {
                            messages.add(LlmMessage.assistant("action " + i));
                            if (i % 3 == 1) messages.add(LlmMessage.toolsAdded(List.of("respond")));
                            String observation = i % 3 != 1 && i % 2 == 0 ? page : "observation " + i;
                            messages.add(named && i == turns - 1 ? LlmMessage.newest(observation, STEP)
                                    : LlmMessage.user(observation));
                            // A step that found tools: its addition comes after the newest user turn.
                            if (i == turns - 1 && i % 2 == 1) messages.add(LlmMessage.toolsAdded(List.of("respond")));
                        }
                        JsonNode body = body(messages, true, farApart);
                        String what = " with a chat of " + chat + " and " + turns + " turns, split=" + split
                                + ", named=" + named + ", farApart=" + farApart + ": " + body;
                        int marks = body.toString().split("cache_control", -1).length - 1;
                        assertTrue(marks <= 4, marks + " marks" + what);
                        var ttls = marks(body).stream().map(JsonNode::toString).toList();
                        assertTrue(ttls.lastIndexOf(HOUR) < Math.max(0, ttls.indexOf(FIVE_MINUTES)) || !ttls.contains(FIVE_MINUTES),
                                "the hour-long marks before the five-minute ones: " + ttls + what);
                        if (farApart && split) assertFalse(ttls.contains(FIVE_MINUTES), "every mark for an hour: " + ttls + what);
                        body.path("messages").forEach(m -> m.path("content").forEach(b -> assertFalse(
                                b.path("text").asText("x").isBlank(), "a blank block, which the API refuses: " + body)));
                    }
                }
            }
        }
    }
}
