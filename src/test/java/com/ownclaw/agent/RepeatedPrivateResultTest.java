package com.ownclaw.agent;

import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.llm.CloudGateway;
import com.ownclaw.llm.EgressLedger;
import com.ownclaw.llm.EgressRefused;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.LlmResponse;
import com.ownclaw.llm.ToolCall;
import com.ownclaw.llm.Replies;
import com.ownclaw.privacy.Label;
import com.ownclaw.privacy.PrivateIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.ownclaw.agent.AssistantPartsTest.REPORT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A result whose own facts say PUBLIC but whose bytes repeat a PRIVATE one is labelled PRIVATE --
 * by the same question the gateway asks of what it sends.
 * <p>
 * Before, such a result was shown to the cloud whole, and the canary refused the next call over
 * it: the task ended there, as a privacy block, on a result nothing had labelled wrongly by its
 * facts -- a public skill that printed a file a credentialed skill had written. The label and the
 * door now ask one question, {@link AgentContext#firstLeakIn}, so what the door would refuse of a
 * result is already a description when the cloud is shown it -- but for a coincidence at the
 * result's edges, which that method's javadoc states.
 */
class RepeatedPrivateResultTest {

    private static AgentContext withPrivateAudit(String message) {
        var ctx = new AgentContext("u1", "t1", message);
        ctx.addArtifact("router_audit", Map.of(), Map.of(), REPORT, true,
                Artifact.labelFor(true, List.of()));
        return ctx;
    }

    // ── the label ──

    @Test
    @DisplayName("a result that repeats a private one is private, says which, and is not indexed")
    void aRepeatIsPrivate() {
        var ctx = withPrivateAudit("Audit the routers.");
        var d = ctx.decide(List.of(), List.of(), false, "cat /srv/audit.md:\n" + REPORT);

        assertEquals(Label.PRIVATE, d.label());
        assertEquals(List.of("repeats {{1}}"), d.why());
        assertFalse(d.indexed(), "the run it repeats is {{1}}'s, and {{1}} is indexed");
    }

    @Test
    @DisplayName("a repeat of what the cloud was already given stays public")
    void anExcusedRepeatIsPublic() {
        String message = "Check that wireless.guest.ssid='guest-net' stays isolated from the LAN.";
        var ctx = new AgentContext("u1", "t1", message);
        // The private result quotes the task; so does the public one. Only the task's own text is
        // shared -- the brackets differ -- so every run they share is one the owner wrote.
        ctx.addArtifact("router_audit", Map.of(), Map.of(), "Audited [" + message + "] -- isolated", true,
                Artifact.labelFor(true, List.of()));

        var d = ctx.decide(List.of(), List.of(), false, "Saved the request <" + message + ">");
        assertEquals(Label.PUBLIC, d.label(), "the owner wrote that in the task: " + d.why());
        assertTrue(d.indexed());
    }

    @Test
    @DisplayName("a result that repeats nothing is labelled by its facts alone, as before")
    void noRepeatNoChange() {
        var ctx = withPrivateAudit("Audit the routers.");
        var d = ctx.decide(List.of(), List.of(), false, "The weather in Brno: 14 °C, light rain.");
        assertEquals(Label.PUBLIC, d.label());
        assertTrue(d.indexed());
    }

    // ── one question, asked by the label and by the door ──

    @Test
    @DisplayName("the gateway never refuses a result the label let go, as each renderer sends it")
    void theLabelAndTheDoorAgree() {
        String excusedLine = "Output: a Markdown report that begins exactly with the audit header";
        String secret = REPORT + "\nwireless.default_radio0.key='Qx7-Lm2-Rt9-Wq4z-Pk5-Hn8'";
        String spec = "Devices checked, then one section per device with guest isolation OK or BROKEN";
        String privateResult = excusedLine + "\n" + spec + "\n" + secret;

        var engine = new ThinkingEngine(new ToolRegistry(List.of()), new OwnClawConfig(), null);
        var config = new OwnClawConfig();
        config.getMentor().setProvider("anthropic");
        var cloud = new AssistantPartsTest.Scripted(List.of(AssistantPartsTest.call("respond", Map.of("message", "ok"))));
        var gateway = new CloudGateway(cloud, cloud, config, row -> { }, null);

        // Texts built from what a result can carry: fresh words, runs of the private result,
        // text the owner or the cloud wrote that the private result repeats, in any mix. (What the
        // frame's own characters can add at a result's edges is stated on AgentContext.firstLeakIn
        // and pinned in PrivateIndexTest; none of these texts meets it.)
        var random = new Random(20260930);
        String[] fresh = {"status ok ", "the page says hello world again ", "12 items processed\n",
                "Weather: sunny, 21 degrees. ", "{\"ok\": true, \"count\": 3} "};
        int privateSeen = 0, publicSeen = 0;
        for (int n = 0; n < 300; n++) {
            var text = new StringBuilder();
            for (int part = 0; part < 1 + random.nextInt(4); part++) {
                switch (random.nextInt(4)) {
                    case 0 -> text.append(fresh[random.nextInt(fresh.length)]);
                    case 1 -> {
                        int from = random.nextInt(secret.length() - 40);
                        text.append(secret, from, from + 32 + random.nextInt(8));
                    }
                    case 2 -> text.append(excusedLine).append(' ');
                    default -> text.append(spec).append(' ');
                }
            }
            String output = text.toString();

            // The task as the loop holds it when the result comes back: the owner's message, the
            // argument the cloud typed, the private result. The label is decided then...
            var ctx = new AgentContext("u1", "t1", "Audit the routers. " + excusedLine);
            ctx.trajectory().record(new AgentAction("plan_report", Map.of("spec", spec), "planning"),
                    AgentObservation.success("plan_report", "noted", Map.of(), 5));
            var audit = ctx.addArtifact("router_audit", Map.of(), Map.of(), privateResult, true,
                    Artifact.labelFor(true, List.of()));
            ctx.trajectory().record(new AgentAction("router_audit", Map.of(), "auditing"),
                    Artifact.asObservation(audit, ToolResult.success(privateResult), 10));
            boolean labelledPrivate = ctx.decide(List.of(), List.of(), false, output).label() == Label.PRIVATE;

            // ...and the door sees the result as each renderer sends a public one: whole, in its
            // frame, on the next think call.
            ctx.trajectory().record(new AgentAction("cat_report", Map.of(), ""),
                    AgentObservation.success("cat_report", output, Map.of(), 7));
            for (String provider : List.of("anthropic", "openai")) {
                var messages = engine.buildMessages(ctx, provider, new ThinkingEngine.StepMode(true, false, false));
                boolean refused;
                try {
                    gateway.chat(messages, new LlmRequestConfig(null, null, false).withEgress(ctx.egress("think")));
                    refused = false;
                } catch (EgressRefused e) {
                    refused = true;
                }
                // The door reads the result filtered, the label as it is: a run that holds an
                // identifier or a secret is not in what the door sends, so the door can pass what
                // the label withholds. Never the reverse, which would end the task at the door.
                assertTrue(!refused || labelledPrivate, provider + ": the door refused what the label let go: " + output);
            }
            if (labelledPrivate) privateSeen++; else publicSeen++;
        }
        assertTrue(privateSeen > 50 && publicSeen > 50,
                "both answers were exercised: " + privateSeen + " private, " + publicSeen + " public");
    }

    /** A cloud behind the real gateway, under the name of either renderer's provider. */
    static final class Named implements LlmProvider {
        final String name;
        final List<ToolCall> script;
        final List<List<LlmMessage>> requests = new ArrayList<>();
        Named(String name, ToolCall... script) {
            this.name = name;
            this.script = List.of(script);
        }
        public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
            requests.add(m);
            return Replies.of("", 100, 10, 0, 0, "tool_use",
                    List.of(script.get(Math.min(requests.size(), script.size()) - 1)));
        }
        public boolean isAvailable() { return true; }
        public boolean supportsTools() { return true; }
        public String name() { return name; }
        public String model() { return "claude-opus-5"; }
    }

    private static AgentResult run(Path db, Named cloud, ToolRegistry registry, List<EgressLedger.Row> rows,
                                   AgentContext ctx) throws Exception {
        var config = new OwnClawConfig();
        config.getMentor().setProvider(cloud.name);
        var gateway = new CloudGateway(cloud, cloud, config, rows::add, null);
        return AssistantPartsTest.loop(MigratedDatabase.at(db), registry, gateway, config).run(ctx);
    }

    @Test
    @DisplayName("through the real loop, on both renderers: a public result repeating the cloud's words is sent, though a private one quotes them too")
    void anEchoOfTheCloudsWordsIsSent(@TempDir Path tmp) throws Exception {
        String typed = "Guest SSID must stay isolated from the LAN at all times.";
        for (String provider : List.of("anthropic", "openai")) {
            var registry = new ToolRegistry(List.of(
                    AssistantPartsTest.tool("store_note", List.of("NOTES_TOKEN"), p -> "Stored note: " + p.get("note")),
                    AssistantPartsTest.tool("print_text", List.of(), p -> String.valueOf(p.get("text")))));
            var cloud = new Named(provider,
                    AssistantPartsTest.call("store_note", Map.of("note", typed)),
                    AssistantPartsTest.call("print_text", Map.of("text", typed)),
                    AssistantPartsTest.call("respond", Map.of("message", "Noted.")));
            var rows = new ArrayList<EgressLedger.Row>();
            var ctx = new AgentContext("u1", "t-echo", "Keep a note about the guest network.");

            AgentResult r = run(tmp.resolve(provider + ".db"), cloud, registry, rows, ctx);

            // The private note quotes the words after a space; rendered, the public result starts
            // after a line break -- a space too, once normalised.
            assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), provider + ": " + r.response());
            assertTrue(rows.stream().allMatch(x -> x.decision() == EgressLedger.Decision.SENT), provider);
            assertEquals(Label.PUBLIC, ctx.artifacts().get(1).label(), provider + ": every run of it is the cloud's");
            var last = cloud.requests.get(cloud.requests.size() - 1);
            assertTrue(last.stream().anyMatch(m -> m.role() == LlmMessage.Role.USER && m.content().contains(typed)),
                    provider + ": the public result reached the cloud whole");
        }
    }

    @Test
    @DisplayName("through the real loop, on both renderers: the confirmation that quotes the digest it sent does not stop the task")
    void aConfirmationQuotingTheDigestIsNotADeadEnd(@TempDir Path tmp) throws Exception {
        String digest = "Daily digest, 30 September: markets calm, rain in Brno at 14 degrees, "
                + "and the council approved the tram line to the campus.";
        for (String provider : List.of("anthropic", "openai")) {
            var registry = new ToolRegistry(List.of(
                    AssistantPartsTest.tool("daily_news_digest", List.of(), p -> digest),
                    AssistantPartsTest.tool("smtp_send_email", List.of("SMTP_PASS"),
                            p -> "Sent to owner@example.org:\n" + p.get("body"))));
            var cloud = new Named(provider,
                    AssistantPartsTest.call("daily_news_digest", Map.of()),
                    AssistantPartsTest.call("smtp_send_email", Map.of("body", "{{1}}")),
                    AssistantPartsTest.call("respond", Map.of("message", "Sent.")));
            var rows = new ArrayList<EgressLedger.Row>();
            var ctx = new AgentContext("u1", "t-digest", "Email me the morning digest.");

            AgentResult r = run(tmp.resolve(provider + ".db"), cloud, registry, rows, ctx);

            // The case the order of the excuses exists to allow: the digest is public and came
            // first; the confirmation after it quotes it, after a colon and a line break.
            assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), provider + ": " + r.response());
            assertTrue(rows.stream().allMatch(x -> x.decision() == EgressLedger.Decision.SENT), provider);
            assertEquals(3, cloud.requests.size(), provider);
        }
    }

    @Test
    @DisplayName("through the real loop, on both renderers: a delegation that sent the digest and stopped short hands its work back")
    void anUnfinishedDelegationThatSentTheDigestIsNotADeadEnd(@TempDir Path tmp) throws Exception {
        String digest = "Daily digest, 30 September: markets calm, rain in Brno at 14 degrees, "
                + "and the council approved the tram line to the campus.";
        for (String provider : List.of("anthropic", "openai")) {
            for (String stop : List.of("three turns ran nothing", "the local model's next call fails")) {
                var news = new DelegationBehaviourTest.FakeTool("daily_news_digest", false, List.of(),
                        p -> ToolResult.success(digest));
                var smtp = new DelegationBehaviourTest.FakeTool("smtp_send_email", true, List.of("SMTP_PASS"),
                        p -> ToolResult.success("Sent to owner@example.org:\n" + p.get("body")));
                var registry = new ToolRegistry(List.of(news, smtp));
                // The local model fetches the digest and sends it; then the delegation ends
                // unfinished -- the hand-back to the cloud that a failing local tier relies on.
                var turns = new java.util.ArrayDeque<>(List.of(
                        DelegationBehaviourTest.call("daily_news_digest", Map.of()),
                        DelegationBehaviourTest.call("smtp_send_email", Map.of("to", "owner@example.org", "body", "{{1}}"))));
                LlmProvider local = new LlmProvider() {
                    public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
                        if (!turns.isEmpty()) return Replies.of(turns.poll(), 1, 1);
                        if ("three turns ran nothing".equals(stop)) return Replies.of("", 1, 1);
                        throw new com.ownclaw.llm.LlmException("ollama", "Read timed out");
                    }
                    public boolean isAvailable() { return true; }
                    public String name() { return "ollama"; }
                };
                var cloud = new Named(provider,
                        AssistantPartsTest.call("delegate", Map.of("goal", "Fetch the morning digest and email it to the owner.",
                                "tools", "daily_news_digest,smtp_send_email")),
                        AssistantPartsTest.call("respond", Map.of("message", "The digest was sent.")));
                var rows = new ArrayList<EgressLedger.Row>();
                var config = new OwnClawConfig();
                config.getMentor().setProvider(provider);
                var gateway = new CloudGateway(cloud, cloud, config, rows::add, null);
                var jdbc = MigratedDatabase.at(tmp.resolve(provider + "-" + stop.length() + ".db"));
                var emitter = new com.ownclaw.observability.ChatStatusEmitter();
                var events = new com.ownclaw.observability.EventLogService(jdbc);
                var router = new LlmRouter(local, gateway, config, null);
                var loop = new AgentLoop(new ThinkingEngine(registry, config, router), new CriticAgent(registry),
                        registry, emitter, config, router, null, new SkillCuratorService(jdbc, null, null),
                        new AssistantPartsTest.NoSkills(), new com.ownclaw.observability.DebugSessionService(),
                        new com.ownclaw.core.TaskCancellationService(), null, null,
                        new com.ownclaw.core.LongRunningTaskManager(jdbc, emitter, events, config), null,
                        new com.ownclaw.core.TokenBudgetTracker(jdbc, config, emitter), events, null,
                        new LocalExecutor(new LlmRouter(local, null, null, null), registry, emitter,
                                new DelegationBehaviourTest.Usage(), config), null, new com.ownclaw.privacy.Redactor(null));
                var ctx = new AgentContext("u1", "t-partial", "Email me the morning digest.");
                ctx.setLocalTierReady(false);
                // The confirmation stands for a private result that quotes a public one.
                ctx.setPersonalSources(List.of("SMTP_"));

                AgentResult r = loop.run(ctx);

                String what = provider + ", " + stop + ": ";
                assertEquals(1, smtp.calls.size(), what + "the email went out once");
                assertEquals(Label.PRIVATE, ctx.artifacts().get(1).label(), what);
                assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), what + r.response());
                assertTrue(rows.stream().allMatch(x -> x.decision() == EgressLedger.Decision.SENT), what + rows);
                assertEquals(2, cloud.requests.size(), what + "the report was sent, and the cloud finished");
                String report = ctx.trajectory().turns().get(0).observation().output();
                assertTrue(report.startsWith("Delegation incomplete: "), what + report);
                assertTrue(report.contains("### {{1}}: daily_news_digest ✓\n" + digest), what + report);
            }
        }
    }

    // ── what a label costs ──

    @Test
    @DisplayName("a 200,000-character digest a private confirmation quoted is labelled and sent in moments")
    void aQuotedDigestCostsMomentsNotMinutes() {
        var words = new StringBuilder();
        var random = new Random(7);
        String[] w = {"markets", "calm", "rain", "Brno", "tram", "council", "approved", "campus", "degrees"};
        while (words.length() < 200_000) words.append(w[random.nextInt(w.length)]).append(random.nextInt(1000)).append(' ');
        String digest = words.toString();

        // Asked window by window, a quarter of this took eleven seconds to label, and the door
        // paid as much again on every think call that sent the digest.
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            var ctx = new AgentContext("u1", "t1", "Email me the morning digest.");
            var published = ctx.addArtifact("daily_news_digest", Map.of(), Map.of(), digest, true,
                    Artifact.labelFor(false, List.of()));
            ctx.trajectory().record(new AgentAction("daily_news_digest", Map.of(), ""),
                    Artifact.asObservation(published, ToolResult.success(digest), 10));
            var sent = ctx.addArtifact("smtp_send_email", Map.of(), Map.of(), "Sent: \"" + digest + "\"", true,
                    Artifact.labelFor(true, List.of()));
            ctx.trajectory().record(new AgentAction("smtp_send_email", Map.of("body", "{{1}}"), ""),
                    Artifact.asObservation(sent, ToolResult.success(sent.output()), 10));

            assertEquals(Label.PUBLIC, ctx.decide(List.of(), List.of(), false, "Again:\n" + digest).label());
            var cloud = new AssistantPartsTest.Scripted(List.of(AssistantPartsTest.call("respond", Map.of("message", "ok"))));
            var messages = AssistantPartsTest.render(ctx);
            assertDoesNotThrow(() -> AssistantPartsTest.gateway(cloud, new ArrayList<>())
                    .chat(messages, new LlmRequestConfig(null, null, false).withEgress(ctx.egress("think"))));
        });
    }

    @Test
    @DisplayName("a label, or a call to the cloud, reads each source once, however many runs it asks about")
    void eachSourceIsReadOncePerQuestion() {
        // A credentialed skill failed, and its traceback quotes forty lines of its source. The
        // cloud then reads the source -- a public result in which every quoted line is a run of
        // the traceback, excused by the source of the skill that failed, the last of the sources
        // asked. Each of the forty is its own stretch, and each goes past every source before it.
        var lines = new ArrayList<String>();
        for (int i = 0; i < 40; i++) {
            lines.add("    status_" + i + " = fetch_router_status(host_" + i + ", timeout=" + (10 + i) + ")");
        }
        String source = "def run(params):\n" + String.join("\n", lines);
        var traceback = new StringBuilder("Traceback (most recent call last):\n");
        for (int i = 0; i < lines.size(); i++) {
            traceback.append("  File \"/skills/router_audit/skill.py\", line ").append(i + 2)
                    .append(", in run\n").append(lines.get(i)).append('\n');
        }
        var ctx = new AgentContext("u1", "t1", "Audit the routers.");
        int[] reads = {0, 0};
        ctx.setSkillSource(tool -> {
            reads[0]++;
            return "router_audit".equals(tool) ? source : null;
        });
        // An argument the cloud typed: a source too, read -- String.valueOf -- each time it is met.
        Object typed = new Object() {
            @Override public String toString() {
                reads[1]++;
                return "all routers on the LAN";
            }
        };
        ctx.trajectory().record(new AgentAction("plan_audit", Map.of("scope", typed), "planning"),
                AgentObservation.success("plan_audit", "noted", Map.of(), 5));
        ctx.addArtifact("router_audit", Map.of(), Map.of(), traceback.toString(), false,
                Artifact.labelFor(true, List.of()));

        var d = ctx.decide(List.of(), List.of(), false, source);

        assertEquals(Label.PUBLIC, d.label(), "every run of it is the skill's own source: " + d.why());
        assertArrayEquals(new int[] {1, 1}, reads, "each source read once for the label, not once per run");

        // The door, on one call: the same forty stretches in the part that carries the source.
        reads[0] = reads[1] = 0;
        var cloud = new AssistantPartsTest.Scripted(List.of(AssistantPartsTest.call("respond", Map.of("message", "ok"))));
        var egress = ctx.egress("think");
        AssistantPartsTest.gateway(cloud, new ArrayList<>()).chat(List.of(LlmMessage.system("S"),
                LlmMessage.user("[skill_manage] OK (3ms)\n" + source), LlmMessage.user("Fix it.")),
                new LlmRequestConfig(null, null, false).withEgress(egress));
        assertArrayEquals(new int[] {1, 1}, reads, "each source read once for the call");
    }

    // ── what a run cannot tell ──

    @Test
    @DisplayName("boilerplate in a private result withholds a public result that carries it too: a traceback's first line")
    void boilerplateIsWithheldWhole() {
        // AgentContext.decide says so: a run is 32 characters, and Python's own header is one.
        // After a credentialed skill fails with a traceback, a public skill's traceback repeats
        // it and is withheld whole -- the cloud reads that the call failed, not why. The same
        // collision ended the task at the door before.
        var ctx = new AgentContext("u1", "t1", "Check the mailbox, then fetch the status page.");
        ctx.addArtifact("imap_fetch", Map.of(), Map.of(), "Skill error:\nTraceback (most recent call last):\n"
                        + "  File \"/skills/imap_fetch/skill.py\", line 12, in run\n"
                        + "    box = imaplib.IMAP4_SSL(params['host'])\nKeyError: 'host'", false,
                Artifact.labelFor(true, List.of()));
        String publicError = "Skill error:\nTraceback (most recent call last):\n"
                + "  File \"/skills/web_fetch/skill.py\", line 5, in run\n"
                + "    r = requests.get(url, timeout=10)\n"
                + "requests.exceptions.ConnectionError: [Errno 111] Connection refused";

        var d = ctx.decide(List.of(), List.of(), false, publicError);

        assertEquals(Label.PRIVATE, d.label());
        assertEquals(List.of("repeats {{1}}"), d.why());
    }

    // ── the task goes on ──

    @Test
    @DisplayName("through the real loop: a public skill that prints the private report no longer ends the task")
    void theTaskGoesOn(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var config = new OwnClawConfig();
        config.getMentor().setProvider("anthropic");
        var registry = new ToolRegistry(List.of(
                AssistantPartsTest.tool("router_audit", List.of("ROUTER_USER", "ROUTER_PASS"), p -> REPORT),
                // Reads back the file the audit wrote: no credentials and no reference, so its
                // own facts say PUBLIC. Its bytes are the audit's.
                AssistantPartsTest.tool("cat_report", List.of(), p -> REPORT)));
        var cloud = new AssistantPartsTest.Scripted(List.of(
                AssistantPartsTest.call("router_audit", Map.of()),
                AssistantPartsTest.call("cat_report", Map.of("path", "/srv/reports/audit.md")),
                AssistantPartsTest.call("respond", Map.of("message", "{{2}}"))));
        var rows = new ArrayList<EgressLedger.Row>();
        var ctx = new AgentContext("u1", "t-cat", "Audit the routers and show me the saved report.");
        ctx.setPersonalSources(List.of("ROUTER_"));   // the audit stands for a private result

        AgentResult r = AssistantPartsTest.loop(jdbc, registry, AssistantPartsTest.gateway(cloud, rows), config)
                .run(ctx);

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(),
                "the step after cat_report was refused over the audit's bytes: " + r.response());
        assertTrue(rows.stream().allMatch(x -> x.decision() == EgressLedger.Decision.SENT));
        var printed = ctx.artifacts().get(1);
        assertEquals(Label.PRIVATE, printed.label());
        assertEquals(List.of("repeats {{1}}"), printed.why());
        assertTrue(r.ownerText() != null && r.ownerText().contains("guest-net"), "the owner got the report");
        // What the cloud was sent after cat_report: its description, never the report.
        var last = cloud.requests.get(cloud.requests.size() - 1);
        String audit = PrivateIndex.normalise(REPORT).substring(80, 112);
        assertTrue(last.stream().noneMatch(m -> PrivateIndex.normalise(m.content()).contains(audit)));
    }

    @Test
    @DisplayName("in a delegation, a tool that repeats a private result is private, and so is all it reads after")
    void aDelegationThatReadsARepeatIsTainted() {
        var ctx = withPrivateAudit("Summarise the saved audit.");
        ctx.setUnattended(true);
        var cat = new DelegationBehaviourTest.FakeTool("cat_report", false, List.of(),
                p -> ToolResult.success(REPORT));
        var search = new DelegationBehaviourTest.FakeTool("web_search", false, List.of(),
                p -> ToolResult.success("{\"results\": [\"OpenWrt guest network guide\"]}"));
        var llm = new DelegationBehaviourTest.Scripted(
                DelegationBehaviourTest.call("cat_report", Map.of("path", "/srv/reports/audit.md")),
                DelegationBehaviourTest.call("web_search", Map.of("q", "guest isolation")),
                DelegationBehaviourTest.done("summarised"));

        DelegationBehaviourTest.executor(llm, new DelegationBehaviourTest.Usage(), cat, search)
                .execute(DelegationBehaviourTest.plan("summarise the audit"), ctx, DelegationBehaviourTest.UNCOUNTED);

        var read = ctx.artifacts().get(1);
        assertEquals(Label.PRIVATE, read.label());
        assertEquals(List.of("repeats {{1}}"), read.why());
        assertEquals(List.of("after private data in this delegation"), ctx.artifacts().get(2).why(),
                "the local model has read the audit now; what it does next can carry it");
        assertTrue(ctx.localTierReadPrivate());
    }
}
