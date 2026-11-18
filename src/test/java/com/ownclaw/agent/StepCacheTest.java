package com.ownclaw.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.FileStorageService;
import com.ownclaw.llm.CloudGateway;
import com.ownclaw.llm.CloudImages;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.LlmResponse;
import com.ownclaw.llm.Replies;
import com.ownclaw.llm.RequestBodies;
import com.ownclaw.llm.ToolCall;
import com.ownclaw.privacy.Redactor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static com.ownclaw.agent.LoopRig.call;
import static com.ownclaw.agent.LoopRig.respond;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A task's steps as Anthropic's prompt cache meets them, request after request, through the real
 * engine, gateway and provider: each request marks its newest result, and the next one sends the
 * same blocks up to that mark, so it reads what the last one wrote and pays in full only for what
 * changes on every step; and a task whose think requests have been further apart than a
 * five-minute entry lives caches its steps for an hour from there.
 * <p>
 * The mark stood on the message before the newest result: every result was billed in full in the
 * request that first carried it and written to the cache in the next -- 26% of four days' bill --
 * and a step longer than five minutes made the next request write every step again, $3.42 of one
 * $12.85 chat.
 */
class StepCacheTest {

    static final String MODEL = "claude-opus-5-5";
    /** A vault value: the gateway takes it out of every part. */
    static final String VAULT_VALUE = "tr0ub4dor-and-3";
    /** A pasted image's copy, as the image store holds it. */
    static final byte[] PIXELS = "PNG-PIXELS-OF-A-SCREENSHOT".getBytes(StandardCharsets.UTF_8);

    static final ThinkingEngine.StepMode CLOUD = new ThinkingEngine.StepMode(true, false, false);

    // ── a request, as the cache reads it ──

    /**
     * A block of a request's messages: whose message, what it is, its bytes -- a text's, an
     * image's, the name a tool addition offers -- and how long its cache mark lives, or null.
     */
    record Block(String role, String type, String bytes, String mark) {
        /** The block as a later request has to send it for an entry cached at or after it to be found. */
        Block unmarked() {
            return new Block(role, type, bytes, null);
        }
    }

    /** Every block of {@code body}'s messages, in order; a message that is one text is one block. */
    static List<Block> blocks(JsonNode body) {
        var out = new ArrayList<Block>();
        for (JsonNode m : body.path("messages")) {
            String role = m.path("role").asText();
            JsonNode content = m.path("content");
            if (content.isTextual()) {
                out.add(new Block(role, "text", content.asText(), null));
                continue;
            }
            for (JsonNode b : content) {
                String type = b.path("type").asText();
                String bytes = switch (type) {
                    case "text" -> b.path("text").asText();
                    case "image" -> b.path("source").path("media_type").asText() + ":" + b.path("source").path("data").asText();
                    case "tool_addition" -> b.path("tool").path("name").asText();
                    default -> throw new AssertionError("a block this test cannot read: " + b);
                };
                String mark = !b.has("cache_control") ? null
                        : "1h".equals(b.path("cache_control").path("ttl").asText()) ? "1h" : "5m";
                out.add(new Block(role, type, bytes, mark));
            }
        }
        return out;
    }

    /** Where the request's last cache mark stands among its blocks. */
    static int lastMark(List<Block> blocks) {
        for (int i = blocks.size() - 1; i >= 0; i--) if (blocks.get(i).mark() != null) return i;
        throw new AssertionError("no block is marked");
    }

    /**
     * The entry a request can read, as the API finds one: the furthest block that an earlier
     * request marked and that this request holds with every block before it as that request sent
     * them -- the same roles, kinds and bytes. -1 when no earlier mark is one.
     */
    static int read(List<List<Block>> earlier, List<Block> now) {
        int furthest = -1;
        for (List<Block> was : earlier) {
            int same = 0;
            while (same < was.size() && same < now.size() && was.get(same).unmarked().equals(now.get(same).unmarked())) same++;
            for (int i = Math.min(same, was.size()) - 1; i > furthest; i--) {
                if (was.get(i).mark() != null) furthest = i;
            }
        }
        return furthest;
    }

    // ── a task, made by a seed ──

    /** A library like the owner's, and {@code probes} skills more for a task to find. */
    static List<Tool> library(int probes) {
        var tools = new ArrayList<>(ToolFinderTest.library());
        for (int i = 0; i < probes; i++) {
            tools.add(ToolFinderTest.skill(String.format("probe_%03d", i), "Measures quantity " + i + " of the plant.", Map.of()));
        }
        return tools;
    }

    /** A cloud provider that keeps what the gateway hands it. */
    static final class Kept implements LlmProvider {
        List<LlmMessage> messages;
        LlmRequestConfig config;
        final List<LlmRequestConfig> configs = new ArrayList<>();

        @Override
        public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
            messages = m;
            config = c;
            configs.add(c);
            return Replies.of("", 100, 10, 0, 0, "tool_use", List.of(new ToolCall("c-1", "noop", Map.of())));
        }

        @Override public boolean isAvailable() { return true; }
        @Override public boolean supportsTools() { return true; }
        @Override public String name() { return "anthropic"; }
        @Override public String model() { return MODEL; }
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** The real gateway before {@code kept}, with an image store that holds one copy for every file asked for. */
    static CloudGateway gateway(Kept kept) throws Exception {
        var config = new OwnClawConfig();
        config.getMentor().setProvider("anthropic");
        String hash = sha256(PIXELS);
        CloudImages store = (user, file) -> CloudImages.Found.of(new CloudImages.Copy("image/png", 64, 64, PIXELS, hash));
        return new CloudGateway(kept, kept, config, row -> { }, null, new Redactor(null), store);
    }

    static FileStorageService.PastedImage image(String file) {
        return new FileStorageService.PastedImage(file, "image/png", "image/png", 64, 64, PIXELS.length, 64, 64);
    }

    /**
     * What a tool, a page or the owner can write, in pieces: plain lines, what the gateway rewrites
     * -- an address, a key, a MAC, a vault value, a private key whole -- the prompt's own markers
     * and their control character, the line the per-step block begins with, long output, letters
     * past ASCII. No device's name: once the filter learns one from a later result it replaces it
     * in the earlier ones too, which is its rule and rewrites them whatever is cached.
     */
    static String text(Random r) {
        var sb = new StringBuilder();
        for (int pieces = 1 + r.nextInt(6); pieces > 0; pieces--) {
            sb.append(switch (r.nextInt(14)) {
                case 0 -> "contact: user" + r.nextInt(4) + "@example.org\n";
                case 1 -> "\toption key 'passphrase number " + r.nextInt(1000) + "'\n";
                case 2 -> "\toption macaddr '02:00:5e:10:20:" + String.format("%02x", r.nextInt(8)) + "'\n";
                case 3 -> "the router's password is " + VAULT_VALUE + "\n";
                case 4 -> "a marker:" + LlmMessage.MARKERS.get(r.nextInt(LlmMessage.MARKERS.size())) + "quoted\n";
                case 5 -> "an image's:" + LlmMessage.image(r.nextInt(3)) + "and a lone \u0001 and \u0001\u001c\n";
                case 6 -> "\n\n---\n## Environment\n- DateTime: as a result prints it\n";
                case 7 -> "config line " + r.nextInt(100) + "\n".repeat(1 + r.nextInt(3)) + "x".repeat(r.nextInt(4000)) + "\n";
                case 8 -> "Příliš žluťoučký kůň úpěl ďábelské ódy " + r.nextInt(100) + ".\n";
                case 9 -> "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAABG5vbmUfake" + r.nextInt(100)
                        + "\n-----END OPENSSH PRIVATE KEY-----\n";
                case 10 -> "   \n\t\n";
                default -> "line " + r.nextInt(10_000) + " of the output\n";
            });
        }
        return sb.toString();
    }

    /** A task whose every step a seed decides, and every think request it sends, as Anthropic gets it. */
    static final class Run {
        final Random r;
        final AgentContext ctx;
        final ThinkingEngine engine;
        final Kept kept = new Kept();
        final CloudGateway gateway = gateway(kept);
        /** The blocks of every request so far, and what the gateway handed the provider for each. */
        final List<List<Block>> sent = new ArrayList<>();
        final List<List<LlmMessage>> handed = new ArrayList<>();
        final List<JsonNode> bodies = new ArrayList<>();
        /** Whether the step before each request was the loop's own; false for the first. */
        final List<Boolean> afterALoopStep = new ArrayList<>();
        /** The task's tools not yet offered. */
        final List<String> unoffered = new ArrayList<>();
        /**
         * Whether the newest result ends in a private key that never ends: the filter removes
         * such a key to the end of the text, so whatever follows the result in its message -- the
         * per-step block, what the loop says next -- goes with it.
         */
        boolean endsInAnOpenKey;

        /** @param probes how many skills the task can find, beyond the library's own */
        Run(long seed, int probes) throws Exception {
            engine = new ThinkingEngine(new ToolRegistry(library(probes)), new OwnClawConfig(), null);
            r = new Random(seed);
            ctx = new AgentContext("u1", "t-" + seed, "Check the plant and mail user0@example.org.\n" + text(r));
            if (r.nextInt(3) > 0) {
                var chat = new ArrayList<String>();
                for (int i = 0, n = 2 * (1 + r.nextInt(2)); i < n; i++) {
                    chat.add((i % 2 == 0 ? "USER: " : "ASSISTANT: ") + "about the plant, " + i + "\n" + text(r).strip() + "\n");
                }
                ctx.setConversationSummary(AgentContext.CONVERSATION_HEADER + String.join("", chat).strip());
                // An image pasted with the chat's first message, sometimes.
                ctx.setConversation(chat, r.nextBoolean() ? Map.of(0, List.of(image("chat-file"))) : Map.of());
            }
            // An image pasted with the task's own message, sometimes: a block of the first message.
            if (r.nextBoolean()) ctx.addPastedImage(image("task-file"), List.of("pasted image", "image/png"));
            if (r.nextBoolean()) ctx.setUserPreferences("The plant is in hall 2. (the owner said this)");
            ctx.setUsualTools(List.of("smtp_send_email"));
            ctx.setCredentialKeys(List.of("ROUTER_PASSWORD"));
            ctx.setSecretValues(Map.of("ROUTER_PASSWORD", VAULT_VALUE));
            for (int i = 0; i < probes; i++) unoffered.add(String.format("probe_%03d", i));
        }

        /** The think request the task sends now, through the gateway, as the provider builds it. */
        void think(boolean afterLoopStep) {
            List<LlmMessage> messages = engine.buildMessages(ctx, "anthropic", CLOUD);
            gateway.chat(messages, new LlmRequestConfig(null, null, false).withEgress(ctx.egress("think"))
                    .withTools(engine.toolsFor(ctx, CLOUD)));
            JsonNode body = RequestBodies.anthropic(kept.messages, kept.config, MODEL);
            bodies.add(body);
            sent.add(blocks(body));
            handed.add(kept.messages);
            afterALoopStep.add(afterLoopStep);
        }

        /** A step of the model's: a skill run, or a search that offers up to eight skills the task has not been offered. */
        void modelStep(int offered) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (r.nextBoolean()) params.put("to", "user" + r.nextInt(4) + "@example.org");
            if (r.nextBoolean()) params.put("note", "see " + LlmMessage.CACHE_BOUNDARY + " and \u0001");
            if (offered > 0) {
                var found = new ArrayList<String>();
                for (int i = 0; i < offered && !unoffered.isEmpty(); i++) found.add(unoffered.removeFirst());
                ctx.addTaskTools(found);
                ctx.trajectory().record(new AgentAction(AgentAction.FIND_TOOLS, Map.of("query", "probe"), "searching"),
                        AgentObservation.success(AgentAction.FIND_TOOLS, found.size() + " skills match:\n- "
                                + String.join("\n- ", found) + "\n" + text(r), Map.of(), r.nextInt(50)));
            } else {
                String tool = r.nextBoolean() ? "web_fetch" : "openwrt_run";
                endsInAnOpenKey = r.nextInt(12) == 0;
                String output = text(r) + (endsInAnOpenKey ? "-----BEGIN RSA PRIVATE KEY-----\nMIIfakeKEYcutOFF"
                        + r.nextInt(100) + "\n" : "");
                ctx.trajectory().record(new AgentAction(tool, params, r.nextBoolean() ? "" : "next, " + tool),
                        r.nextInt(4) == 0 ? AgentObservation.failure(tool, output, r.nextInt(9_000))
                                : AgentObservation.success(tool, output, Map.of(), r.nextInt(9_000)));
                return;
            }
            endsInAnOpenKey = false;
        }

        /** A step the loop takes itself: a reply that could not be used, a reflection, a message the owner sent. */
        void loopStep() {
            String told = "The loop says:\n" + text(r);
            String tool = List.of(ThinkingEngine.THINKING, "_reflection", AgentLoop.FROM_THE_OWNER_STEP).get(r.nextInt(3));
            ctx.trajectory().record(new AgentAction(tool, Map.of(), told), AgentObservation.failure(tool, told, 0));
        }
    }

    // ── the placement and the prefix ──

    @Test
    @DisplayName("over generated tasks, every request sends the blocks of the one before it up to and including its marked newest result, the same roles and bytes -- markers, redacted text, images and tool additions among them -- and reads it within twenty positions")
    void eachRequestReadsWhatTheLastOneWrote() throws Exception {
        int readTheLast = 0, grown = 0, withImages = 0, withAdditions = 0, withMarkers = 0, redacted = 0;
        for (long seed = 0; seed < 120; seed++) {
            var run = new Run(seed, 80);
            run.think(false);
            for (int step = 0, steps = 3 + run.r.nextInt(8); step < steps; step++) {
                boolean byTheLoop = run.r.nextInt(4) == 0 && !run.endsInAnOpenKey;
                if (byTheLoop) run.loopStep();
                else run.modelStep(run.r.nextInt(3) == 0 ? 1 + run.r.nextInt(8) : 0);
                run.think(byTheLoop);
            }

            for (int n = 0; n < run.sent.size(); n++) {
                List<Block> now = run.sent.get(n);
                JsonNode body = run.bodies.get(n);
                String at = "seed " + seed + ", request " + (n + 1) + ": ";

                // Never more than four marks, the hour-long ones first.
                var marks = new ArrayList<String>();
                body.path("system").forEach(b -> marks.add(b.path("cache_control").path("ttl").asText("5m")));
                now.stream().filter(b -> b.mark() != null).forEach(b -> marks.add(b.mark()));
                assertTrue(marks.size() <= 4, at + marks);
                assertTrue(marks.lastIndexOf("1h") < marks.indexOf("5m") || !marks.contains("5m"), at + marks);

                // From the task's second step on: the sliding mark, on the newest result, five
                // minutes long; after it, only what changes on every step and tools offered since.
                int marked = lastMark(now);
                List<LlmMessage> handed = run.handed.get(n);
                LlmMessage newest = handed.stream().filter(m -> m.role() == LlmMessage.Role.USER).reduce((a, b) -> b).orElseThrow();
                boolean hasSteps = handed.stream().anyMatch(m -> m.role() == LlmMessage.Role.ASSISTANT);
                if (hasSteps) {
                    assertEquals("5m", now.get(marked).mark(), at + "the sliding mark");
                    assertEquals(new Block("user", "text", newest.lasting(), "5m"), now.get(marked),
                            at + "on the beginning of the newest message that lasts");
                    assertTrue(marked == 0 || !"user".equals(now.get(marked - 1).role()),
                            at + "which is that message's first block");
                    String after = newest.content().substring(newest.lasting().length());
                    var rest = now.subList(marked + 1, now.size());
                    assertEquals(after.isEmpty() ? List.of() : List.of(new Block("user", "text", after, null)),
                            rest.stream().filter(b -> !"tool_addition".equals(b.type())).toList(),
                            at + "and the rest of the message after it, no result among it");
                    // A key that never ends takes the per-step block with it: there is none then.
                    assertTrue(after.isEmpty() || after.startsWith("\n\n---\n## Environment\n- Platform: "), at + after);
                } else {
                    assertEquals("1h", now.get(marked).mark(), at + "the task's mark is the last: no sliding mark yet");
                }
                if (n == 0) continue;

                // The request before this one, block for block, up to and including its last mark.
                List<Block> was = run.sent.get(n - 1);
                int cached = lastMark(was);
                boolean wasSliding = "5m".equals(was.get(cached).mark());
                boolean grew = run.afterALoopStep.get(n) && wasSliding;
                for (int i = 0; i < (grew ? cached : cached + 1); i++) {
                    assertEquals(was.get(i).unmarked(), now.get(i).unmarked(),
                            at + "block " + i + " is not the block the request before it sent, of " + (cached + 1) + " it cached");
                }
                assertEquals(run.bodies.get(n - 1).path("system"), body.path("system"), at + "the system prompt");
                assertEquals(run.bodies.get(n - 1).path("tools"), body.path("tools"), at + "the tools");

                int read = read(run.sent.subList(0, n), now);
                if (grew) {
                    // A step the loop took itself joined the newest message: its marked block is a
                    // longer text now, and what was cached under the shorter one is not read.
                    assertTrue(now.get(cached).bytes().startsWith(was.get(cached).bytes())
                            && now.get(cached).bytes().length() > was.get(cached).bytes().length(), at + now.get(cached));
                    assertTrue(read < cached, at + "the entry of the request before it is no beginning of this one");
                    grown++;
                } else {
                    assertEquals(cached, read, at + "it reads what the request before it wrote, up to that request's mark");
                    if (hasSteps && wasSliding) {
                        assertEquals(run.handed.get(n - 1).stream().filter(m -> m.role() == LlmMessage.Role.USER)
                                        .reduce((a, b) -> b).orElseThrow().lasting(), was.get(cached).bytes(),
                                at + "which is the result the gateway named the beginning of");
                    }
                    readTheLast++;
                }
                // Whatever it reads is inside the twenty positions the API looks back over from a
                // mark: the mark's own and nineteen before it.
                assertTrue(read >= 0 && marked - read <= 19, at + "an entry " + (marked - read) + " positions back");
                if (now.stream().anyMatch(b -> "image".equals(b.type()))) withImages++;
                // (A request before the task's first step of the model's reads the task's entry, its own last mark.)
                if (now.subList(Math.min(read + 1, marked), marked).stream().anyMatch(b -> "tool_addition".equals(b.type()))) withAdditions++;
                if (now.get(marked).bytes().contains("\u0001")) withMarkers++;
                if (now.get(marked).bytes().contains("«") || now.get(marked).bytes().contains("<email_")) redacted++;
            }
        }
        // The premise: the tasks held what the rule is about.
        assertTrue(readTheLast > 300 && grown > 60 && withImages > 150 && withAdditions > 80 && withMarkers > 150
                        && redacted > 250,
                readTheLast + " requests that read the one before them, " + grown + " after a step of the loop's own "
                        + "made the marked block longer, " + withImages + " with an image, " + withAdditions
                        + " across a tool addition, " + withMarkers + " whose marked result holds a marker's bytes, "
                        + redacted + " whose marked result the gateway rewrote");
        // Mutation: mark the message before the newest user turn, as before -> the mark is on an
        // assistant block. Take the markers out of the marked block -> a result that holds one is
        // not the block the next request sends. Hand on the beginning as the engine wrote it ->
        // wherever the filter rewrote the result the message names none, and the mark is not on it.
    }

    @Test
    @DisplayName("through forty steps, thirty of them offering eight more skills each, every request finds the entry of the one before it two positions back and the additions between -- ten at most, inside the twenty the API looks back over")
    void theEntryStaysInsideTheLookBack() throws Exception {
        var run = new Run(7, 240);
        run.think(false);
        for (int step = 0; step < 40; step++) {
            run.modelStep(step < 30 ? 8 : 0);
            run.think(false);
        }
        for (int n = 1; n < run.sent.size(); n++) {
            List<Block> now = run.sent.get(n);
            int marked = lastMark(now), read = read(run.sent.subList(0, n), now);
            assertEquals(lastMark(run.sent.get(n - 1)), read, "request " + (n + 1) + " reads the entry of the one before it");
            long additions = now.subList(read + 1, marked).stream().filter(b -> "tool_addition".equals(b.type())).count();
            // The first request's entry is the task's; the tools the task started with stand after it.
            if (n > 1) assertEquals(n <= 31 ? 8 : 0, additions, "request " + (n + 1));
            assertEquals(2 + additions, marked - read, "request " + (n + 1) + ": the model's call, its result, and the additions between");
            assertTrue(marked - read <= 19, "request " + (n + 1) + ": " + (marked - read) + " positions back");
        }
        // So every result is written once, in the request that first carries it: what a request
        // writes begins where the mark of the one before it stood.
        assertEquals(41, run.sent.size());
        // Mutation: put the mark on the newest message's last block, what changes on every step --
        // the usual place in a conversation that only grows -> the mark is a position further on,
        // and no request sends the block the one before it marked: each reads the task's entry
        // alone, further back with every step.
    }

    // ── steps far apart ──

    /** The cache marks of a request's messages and system prompt, in the order the API reads them. */
    static List<String> marks(JsonNode body) {
        var out = new ArrayList<String>();
        body.path("system").forEach(b -> out.add(b.path("cache_control").path("ttl").asText("5m")));
        blocks(body).stream().filter(b -> b.mark() != null).forEach(b -> out.add(b.mark()));
        return out;
    }

    @Test
    @DisplayName("think requests up to four and a half minutes apart keep the five-minute mark; the first sent later than that after the one before it caches the steps for an hour, and so does every one after it")
    void farApartOnceIsAnHourFromThenOn() throws Exception {
        var clock = new AtomicLong(1_000_000);
        var engine = new ThinkingEngine(new ToolRegistry(List.of(LocalModeTest.NOOP)), new OwnClawConfig(), null) {
            @Override
            long now() {
                return clock.get();
            }
        };
        var kept = new Kept();
        CloudGateway gateway = gateway(kept);
        var ctx = new AgentContext("u1", "t1", "watch the backup");
        // How long after the request before it each is sent, and whether the steps are then cached for an hour.
        long threshold = AgentContext.FIVE_MINUTE_CACHE_HOLDS_MS;
        assertEquals(270_000, threshold, "five minutes less half a minute");
        long[] gaps = {0, 120_000, threshold, 1_000, threshold + 1, 1_000, 60_000};
        boolean[] hour = {false, false, false, false, true, true, true};
        for (int i = 0; i < gaps.length; i++) {
            clock.addAndGet(gaps[i]);
            ThinkResult thought = engine.decideNextActionFull(ctx, gateway);
            assertEquals("noop", thought.action().tool());
            assertEquals(hour[i], kept.config.hourLongSteps(), "request " + (i + 1) + ", " + gaps[i] + " ms after the last");
            assertEquals(hour[i], ctx.stepsFarApart());
            List<String> marks = marks(RequestBodies.anthropic(kept.messages, kept.config, MODEL));
            assertEquals(i == 0 ? List.of("1h", "1h") : List.of("1h", "1h", hour[i] ? "1h" : "5m"), marks,
                    "request " + (i + 1) + ": the system prompt, the task" + (i == 0 ? "" : ", the newest result"));
            ctx.trajectory().record(thought.action(), AgentObservation.success("noop", "nothing to report", Map.of(), 5));
        }
        // Mutation: compare with five minutes -> request 5, 4 min 30.001 s after the fourth, keeps
        // the five-minute mark. Forget it once a request follows closely -> requests 6 and 7 go
        // back to five minutes. Count from the task's start -> request 3 is already far apart.
    }

    @Test
    @DisplayName("a skill's code written between two think requests is no think request: six minutes from one to the next are far apart though the code was asked for half way; and the next task starts at five minutes again")
    void codeWrittenInBetweenIsNotCounted(@TempDir Path tmp) throws Exception {
        var clock = new AtomicLong();
        var rig = new LoopRig(tmp, List.of(), 600, new StopWithoutLocalModelTest.Down(),
                (registry, config, router) -> new ThinkingEngine(registry, config, router) {
                    @Override
                    long now() {
                        return clock.get();
                    }
                });
        Map<String, Object> spec = Map.of("name", "router_fix", "description", "Apply the router fixes.", "parameters", "{}");
        // The model takes three minutes to ask for the skill, and its code three more to write.
        rig.cloud.think.add(c -> {
            clock.addAndGet(180_000);
            return call(AgentAction.SKILL_CREATE, spec).answer(c);
        });
        rig.cloud.codegen.add(c -> {
            clock.addAndGet(180_000);
            return SkillCodegenTest.finished(SkillCodegenTest.module("")).answer(c);
        });
        rig.cloud.think.add(respond("The fixes are in."));
        String session = rig.chat.createSession("u1", "Router");

        AgentResult r = rig.turn(session, "fix the router");

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        var think = rig.cloud.calls("think");
        var code = rig.cloud.calls("codegen");
        assertEquals(List.of(2, 1), List.of(think.size(), code.size()));
        assertFalse(think.get(0).config().hourLongSteps(), "a task's first request has none before it");
        assertTrue(think.get(1).config().hourLongSteps(), "sent six minutes after the first");
        assertEquals(List.of("1h", "1h", "1h"),
                marks(RequestBodies.anthropic(think.get(1).messages(), think.get(1).config(), MODEL)));
        assertFalse(code.get(0).config().hourLongSteps(), "the code's request is no step of the task's conversation");
        assertEquals(List.of("5m"), marks(RequestBodies.anthropic(code.get(0).messages(), code.get(0).config(), MODEL)));

        // The chat's next task: its own requests, a minute apart, with the last task's six behind it.
        rig.cloud.think.add(c -> {
            clock.addAndGet(60_000);
            return call(AgentAction.SKILL_CREATE, spec).answer(c);
        });
        rig.cloud.codegen.add(SkillCodegenTest.finished(SkillCodegenTest.module("")));
        rig.cloud.think.add(respond("Done again."));
        clock.addAndGet(3_600_000);
        rig.turn(session, "and once more");
        think = rig.cloud.calls("think");
        assertEquals(4, think.size());
        assertFalse(think.get(2).config().hourLongSteps() || think.get(3).config().hourLongSteps(),
                "a task counts its own requests, from its first");
        assertEquals(List.of("1h", "1h", "1h", "5m"),
                marks(RequestBodies.anthropic(think.get(3).messages(), think.get(3).config(), MODEL)),
                "the system prompt, the chat, the task for an hour; the newest result for five minutes");
        // Mutation: count the code's request as one of the task's (AgentLoop#generateSkillCode) ->
        // the first task's requests are three minutes apart twice, and none is far apart. Keep the
        // count with the chat instead of the task -> the second task's first request is an hour
        // after the last, and its steps are cached for an hour.
    }
}
