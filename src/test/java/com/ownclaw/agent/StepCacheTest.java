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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A task's steps as Anthropic's prompt cache meets them, request after request, through the real
 * engine, gateway and provider: each request marks its newest result, and the next one sends the
 * same blocks up to that mark, so it reads what the last one wrote and pays in full only for what
 * changes on every step. Where the gateway reads a result differently alone than with the per-step
 * block after it, it names no beginning, and the mark stands where it stood before.
 * <p>
 * The mark stood on the message before the newest result: every result was billed in full in the
 * request that first carried it and written to the cache in the next -- 26% of four days' bill.
 */
class StepCacheTest {

    static final String MODEL = "claude-opus-5-5";
    /** A vault value: the gateway takes it out of every part. */
    static final String VAULT_VALUE = "tr0ub4dor-and-3";
    /** A vault value of two lines, stored as a key file is: its last character a line break. */
    static final String VAULT_LINES = "wg-private-key-line-one\nwg-preshared-key-line-two\n";
    /** A pasted image's copy, as the image store holds it. */
    static final byte[] PIXELS = "PNG-PIXELS-OF-A-SCREENSHOT".getBytes(StandardCharsets.UTF_8);

    static final ThinkingEngine.StepMode CLOUD = new ThinkingEngine.StepMode(true, false, false);

    /**
     * Names of devices: three hosts, three networks. A name has no shape the filter could know it
     * by: the filter learns one where a text names it, and from then on replaces it wherever it
     * stands as a word, in the earlier messages too. So a task here names a device before it uses
     * the name. One that a later result taught the filter would be replaced in the results before
     * it from the next request on -- the filter's own rule, which rewrites them whatever is cached.
     */
    static final List<String> DEVICES = List.of("laptop-3", "printer-attic", "raspberry.lan", "Home-Net-5G", "IoT_24", "Guest-Net");
    /** Networks with an apostrophe in the name, which a router's configuration writes in a shell's quoting. */
    static final List<String> QUOTED = List.of("Owner's WiFi", "Kid's Tablets", "Guest's Corner");
    /** A placeholder inside a longer word: what the filter writes only in a text the task declared. */
    static final java.util.regex.Pattern INSIDE_A_WORD = java.util.regex.Pattern.compile("<(host|ssid)_[0-9]+>_(daily|data|guest)");

    /** What the newest result of a task is: how the gateway reads it decides where the mark goes. */
    enum Newest {
        /** Text the filter reads the same alone and with the per-step block after it. */
        PLAIN,
        /** A skill's code as skill_manage shows it, declared as the loop declares it: skill code. */
        CODE,
        /** What a failed run printed, declared as the loop declares it: a skill's output. */
        OUTPUT,
        /** Ends in a private key that never ends: the filter removes to the end of the text, the per-step block too. */
        OPEN_KEY,
        /** Ends in the vault's two-line value without its last line break: with the per-step block after it the value is whole. */
        VAULT_ACROSS,
        /** Names a network in a shell's quoting and uses the name beside it: the first reading learns the name, the second knows it. */
        QUOTED_NAME;

        /** Whether the gateway's reading of it alone is no beginning of its reading of the message. */
        boolean readDifferentlyAlone() {
            return this == VAULT_ACROSS || this == QUOTED_NAME;
        }
    }

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

        @Override
        public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
            messages = m;
            config = c;
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

    static int newestUser(List<LlmMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) if (messages.get(i).role() == LlmMessage.Role.USER) return i;
        throw new AssertionError("no user message");
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
        /** What the newest result was at each request ({@link #newest}). */
        final List<Newest> newestAt = new ArrayList<>();
        /** The task's tools not yet offered. */
        final List<String> unoffered = new ArrayList<>();
        /** The devices no text of the task has named yet, and those one has: only these are used by name. */
        final List<String> unnamed = new ArrayList<>(DEVICES), named = new ArrayList<>();
        final List<String> unquoted = new ArrayList<>(QUOTED);
        /** What the newest result is, with what the loop said after it. */
        Newest newest = Newest.PLAIN;
        /** Every result is plain text: for a test of the steps' count, not of their kinds. */
        boolean plainOnly;

        /** @param probes how many skills the task can find, beyond the library's own */
        Run(long seed, int probes) throws Exception {
            engine = new ThinkingEngine(new ToolRegistry(library(probes)), new OwnClawConfig(), null);
            r = new Random(seed);
            ctx = new AgentContext("u1", "t-" + seed, "Check the plant and mail user0@example.org.\n" + naming() + text());
            if (r.nextInt(3) > 0) {
                var chat = new ArrayList<String>();
                for (int i = 0, n = 2 * (1 + r.nextInt(2)); i < n; i++) {
                    chat.add((i % 2 == 0 ? "USER: " : "ASSISTANT: ") + "about the plant, " + i + "\n" + text().strip() + "\n");
                }
                ctx.setConversationSummary(AgentContext.CONVERSATION_HEADER + String.join("", chat).strip());
                // An image pasted with the chat's first message, sometimes.
                ctx.setConversation(chat, r.nextBoolean() ? Map.of(0, List.of(image("chat-file"))) : Map.of());
            }
            // An image pasted with the task's own message, sometimes: a block of the first message.
            if (r.nextBoolean()) ctx.addPastedImage(image("task-file"), List.of("pasted image", "image/png"));
            if (r.nextBoolean()) ctx.setUserPreferences("The plant is in hall 2. (the owner said this)");
            ctx.setUsualTools(List.of("smtp_send_email"));
            ctx.setCredentialKeys(List.of("ROUTER_PASSWORD", "WG_KEYS"));
            ctx.setSecretValues(Map.of("ROUTER_PASSWORD", VAULT_VALUE, "WG_KEYS", VAULT_LINES));
            for (int i = 0; i < probes; i++) unoffered.add(String.format("probe_%03d", i));
        }

        /** A device named for the first time, as a text names one: a host by its hostname, a network in a router's configuration. */
        String naming() {
            String device = unnamed.remove(r.nextInt(unnamed.size()));
            named.add(device);
            return DEVICES.indexOf(device) < 3 ? "hostname: " + device + "\n"
                    : "config wifi-iface 'radio" + r.nextInt(3) + "'\n\toption ssid '" + device + "'\n";
        }

        /** A device some text of the task has named. */
        String device() {
            return named.get(r.nextInt(named.size()));
        }

        /**
         * What a tool, a page or the owner can write, in pieces: plain lines, what the gateway
         * rewrites -- an address, a key, a MAC, a vault value, a private key whole, a device's name
         * -- the prompt's own markers and their control character, the line the per-step block
         * begins with, long output, letters past ASCII. A device's name inside a longer word stays
         * as it is here: the filter looks inside words only in a text the task declared.
         */
        String text() {
            var sb = new StringBuilder();
            for (int pieces = 1 + r.nextInt(6); pieces > 0; pieces--) {
                sb.append(switch (r.nextInt(17)) {
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
                    case 11 -> unnamed.isEmpty() ? "every device is known\n" : naming();
                    case 12 -> "connected to " + device() + " since boot\n";
                    case 13 -> "/mnt/" + device() + "_data/x is mounted\n";
                    default -> "line " + r.nextInt(10_000) + " of the output\n";
                });
            }
            return sb.toString();
        }

        /** A skill's code, with a device's name as a word of its own and inside a longer word. */
        String code() {
            String device = device();
            return "import os\n\nDEVICE = \"" + device + "\"\nBACKUP = \"/srv/" + device + "_daily\"\nMAIL = 'user" + r.nextInt(4)
                    + "@example.org'\n\ndef run(params):\n    return {'output': DEVICE + ' ' + str(" + r.nextInt(100_000) + ")}\n";
        }

        /** What a failed run of a skill printed, a device's name in it likewise. */
        String printed() {
            String device = device();
            return "ping " + device + ": ok; backup of " + device + " done; /mnt/" + device + "_data/x\n"
                    + "Traceback (most recent call last):\n  File \"skill.py\", line " + (1 + r.nextInt(90)) + ", in run\n"
                    + "KeyError: '" + device + "_guest'\n";
        }

        /** The think request the task sends now, through the gateway, as the provider builds it. */
        void think(boolean afterLoopStep) {
            List<LlmMessage> messages = engine.buildMessages(ctx, "anthropic", CLOUD);
            // The skills the task started with are offered already: a search finds others.
            ctx.toolsAdded().forEach(offered -> unoffered.removeAll(offered.names()));
            gateway.chat(messages, new LlmRequestConfig(null, null, false).withEgress(ctx.egress("think"))
                    .withTools(engine.toolsFor(ctx, CLOUD)));
            JsonNode body = RequestBodies.anthropic(kept.messages, kept.config, MODEL);
            bodies.add(body);
            sent.add(blocks(body));
            handed.add(kept.messages);
            afterALoopStep.add(afterLoopStep);
            newestAt.add(newest);
        }

        /** A step of the model's: a search that offers up to eight skills the task has not been offered, or a skill run. */
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
                                + String.join("\n- ", found) + "\n" + text(), Map.of(), r.nextInt(50)));
                newest = Newest.PLAIN;
                return;
            }
            newest = plainOnly ? Newest.PLAIN : switch (r.nextInt(14)) {
                case 0 -> Newest.OPEN_KEY;
                case 1 -> Newest.VAULT_ACROSS;
                case 2 -> unquoted.isEmpty() ? Newest.PLAIN : Newest.QUOTED_NAME;
                case 3, 4 -> Newest.CODE;
                case 5 -> Newest.OUTPUT;
                default -> Newest.PLAIN;
            };
            String output = switch (newest) {
                case PLAIN -> text();
                case OPEN_KEY -> text() + "-----BEGIN RSA PRIVATE KEY-----\nMIIfakeKEYcutOFF" + r.nextInt(100) + "\n";
                case VAULT_ACROSS -> text() + VAULT_LINES.stripTrailing();
                case QUOTED_NAME -> {
                    String network = unquoted.remove(r.nextInt(unquoted.size()));
                    yield text() + "config wifi-iface 'radio1'\n\toption ssid '" + network.replace("'", "'\\''") + "'\n"
                            + "connected to " + network + " since boot\n";
                }
                // Declared before the request that first holds it, as the loop declares what
                // skill_manage read and what a run printed (AgentLoop).
                case CODE -> {
                    String code = code();
                    ctx.declareSkillCode(code, Redactor.Declared.CODE);
                    yield "--- skill.py ---\n" + code;
                }
                case OUTPUT -> {
                    String printed = printed();
                    ctx.declareSkillCode(printed, Redactor.Declared.OUTPUT);
                    yield printed;
                }
            };
            String tool = newest == Newest.CODE ? AgentAction.SKILL_MANAGE : r.nextBoolean() ? "web_fetch" : "openwrt_run";
            boolean failed = newest == Newest.OUTPUT || newest != Newest.CODE && r.nextInt(4) == 0;
            ctx.trajectory().record(new AgentAction(tool, params, r.nextBoolean() ? "" : "next, " + tool),
                    failed ? AgentObservation.failure(tool, output, r.nextInt(9_000))
                            : AgentObservation.success(tool, output, Map.of(), r.nextInt(9_000)));
        }

        /** A step the loop takes itself: a reply that could not be used, a reflection, a message the owner sent. */
        void loopStep() {
            String told = "The loop says:\n" + text();
            String tool = List.of(ThinkingEngine.THINKING, "_reflection", AgentLoop.FROM_THE_OWNER_STEP).get(r.nextInt(3));
            ctx.trajectory().record(new AgentAction(tool, Map.of(), told), AgentObservation.failure(tool, told, 0));
            // What the loop said stands after the result in its message from here on: the vault's
            // value has its line break in either reading, and the network's name is known to both.
            if (newest.readDifferentlyAlone()) newest = Newest.PLAIN;
        }
    }

    // ── the placement and the prefix ──

    @Test
    @DisplayName("over generated tasks, every request sends the blocks of the one before it up to and including its mark, the same roles and bytes -- markers, redacted text, declared code, images and tool additions among them -- and reads it within twenty positions; the mark is on the newest result exactly when the next step sends that beginning, and on the message before it otherwise")
    void eachRequestReadsWhatTheLastOneWrote() throws Exception {
        var seen = new java.util.EnumMap<Newest, Integer>(Newest.class);
        int readTheLast = 0, grown = 0, fellBack = 0, withImages = 0, withAdditions = 0, withMarkers = 0, redacted = 0;
        for (long seed = 0; seed < 150; seed++) {
            var run = new Run(seed, 80);
            run.think(false);
            for (int step = 0, steps = 3 + run.r.nextInt(8); step < steps; step++) {
                // What the loop says after a key that never ends is removed with the key.
                boolean byTheLoop = run.r.nextInt(4) == 0 && run.newest != Newest.OPEN_KEY;
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

                int marked = lastMark(now);
                List<LlmMessage> handed = run.handed.get(n);
                LlmMessage newest = handed.get(newestUser(handed));
                boolean hasSteps = handed.stream().anyMatch(m -> m.role() == LlmMessage.Role.ASSISTANT);
                Newest is = run.newestAt.get(n);
                var rest = now.subList(marked + 1, now.size()).stream().filter(b -> !"tool_addition".equals(b.type())).toList();
                if (!hasSteps) {
                    assertEquals("1h", now.get(marked).mark(), at + "the task's mark is the last: no sliding mark yet");
                } else if (newest.lasting() != null) {
                    // The sliding mark, on the newest result, five minutes long; after it, only
                    // what changes on every step and the tools offered since.
                    assertFalse(is.readDifferentlyAlone(), at + "a beginning is named of a result the filter reads otherwise alone, "
                            + "which the next step does not send: " + is);
                    assertEquals(new Block("user", "text", newest.lasting(), "5m"), now.get(marked),
                            at + "the sliding mark, on the beginning of the newest message that lasts");
                    assertTrue(marked == 0 || !"user".equals(now.get(marked - 1).role()),
                            at + "which is that message's first block");
                    String after = newest.content().substring(newest.lasting().length());
                    assertEquals(after.isEmpty() ? List.of() : List.of(new Block("user", "text", after, null)), rest,
                            at + "and the rest of the message after it, no result among it");
                    assertEquals(is == Newest.OPEN_KEY, after.isEmpty(),
                            at + "only a key that never ends takes the per-step block with it: " + is);
                    assertTrue(after.isEmpty() || after.startsWith("\n\n---\n## Environment\n- Platform: "), at + after);
                    if (is == Newest.CODE || is == Newest.OUTPUT) {
                        assertTrue(INSIDE_A_WORD.matcher(now.get(marked).bytes()).find(),
                                at + "the premise: in a declared text a name is replaced inside a longer word: " + now.get(marked).bytes());
                    }
                } else {
                    // No beginning named: the mark where it stood before, on the model's call, and
                    // the newest message after it as the one text it is.
                    assertTrue(is.readDifferentlyAlone(), at + "the gateway named no beginning of a result it reads alone as "
                            + "it reads it with what follows: " + is);
                    assertEquals(new Block("assistant", "text", now.get(marked).bytes(), "5m"), now.get(marked),
                            at + "the sliding mark, on the model's call before the newest result");
                    assertEquals(List.of(new Block("user", "text", newest.content(), null)), rest,
                            at + "and the newest message after it, whole");
                    fellBack++;
                }
                if (hasSteps) seen.merge(is, 1, Integer::sum);
                if (n == 0) continue;

                // The request before this one, block for block, up to and including its last mark.
                List<Block> was = run.sent.get(n - 1);
                int cached = lastMark(was);
                boolean onAResult = "5m".equals(was.get(cached).mark()) && "user".equals(was.get(cached).role());
                boolean grew = run.afterALoopStep.get(n) && onAResult;
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
                    readTheLast++;
                }
                // Whatever it reads is inside the twenty positions the API looks back over from a
                // mark: the mark's own and nineteen before it.
                assertTrue(read >= 0 && marked - read <= 19, at + "an entry " + (marked - read) + " positions back");

                // The gateway named the beginning of the request before exactly when this request
                // sends that beginning: the message that was the newest then, whole now.
                List<LlmMessage> before = run.handed.get(n - 1);
                if (!run.afterALoopStep.get(n) && before.stream().anyMatch(m -> m.role() == LlmMessage.Role.ASSISTANT)) {
                    LlmMessage then = before.get(newestUser(before)), whole = handed.get(newestUser(before));
                    assertEquals(then.content().startsWith(whole.content()), then.lasting() != null,
                            at + "a beginning is named when the next step sends it, and only then (" + run.newestAt.get(n - 1) + ")");
                    if (then.lasting() != null) assertEquals(then.lasting(), whole.content(), at + "and it is that message");
                }
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
        for (Newest kind : Newest.values()) {
            assertTrue(seen.getOrDefault(kind, 0) >= 20, "the premise: results of every kind: " + seen);
        }
        assertEquals(seen.get(Newest.VAULT_ACROSS) + seen.get(Newest.QUOTED_NAME), fellBack,
                "the mark stood on the message before for those the filter reads differently alone, and for no other: " + seen);
        // Mutation: mark the message before the newest user turn, as before -> the mark is on an
        // assistant block. Take the markers out of the marked block -> a result that holds one is
        // not the block the next request sends. Hand on the beginning as the engine wrote it ->
        // wherever the filter rewrote the result the message names none, and the mark is not on it.
        // Read the beginning the second time without the texts the task declared -> none is named
        // for a skill's code or output. Name it without checking that the message begins with it
        // -> one is named for a network's quoted name, which the next step does not send; read it
        // before the message whole -> none is named for that result though the next step sends it.
        // No mark where no beginning is named -> such a request caches none of its steps.
    }

    @Test
    @DisplayName("through forty steps, thirty of them offering eight more skills each, every request finds the entry of the one before it two positions back and the additions between -- ten at most, inside the twenty the API looks back over")
    void theEntryStaysInsideTheLookBack() throws Exception {
        var run = new Run(7, 260);
        run.plainOnly = true;
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
}
