package com.ownclaw.agent;

import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.llm.CloudGateway;
import com.ownclaw.llm.EgressLedger;
import com.ownclaw.llm.EgressRefused;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.privacy.Label;
import com.ownclaw.privacy.PrivateIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
 * door now ask one question, {@link AgentContext#firstLeakIn}, so what the door would refuse is
 * already a description when the cloud is shown it.
 */
class RepeatedPrivateResultTest {

    private static AgentContext withPrivateAudit(String message) {
        var ctx = new AgentContext("u1", "t1", message);
        ctx.addArtifact("router_audit", Map.of(), Map.of(), REPORT, true,
                Artifact.labelFor(List.of("ROUTER_PASS"), List.of()));
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
                Artifact.labelFor(List.of("ROUTER_PASS"), List.of()));

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
    @DisplayName("the label is PRIVATE exactly when the gateway would refuse to send the result")
    void theLabelAndTheDoorCannotDisagree() {
        String excusedLine = "Output: a Markdown report that begins exactly with the audit header";
        String secret = REPORT + "\nwireless.default_radio0.key='Qx7-Lm2-Rt9-Wq4z-Pk5-Hn8'";
        var ctx = new AgentContext("u1", "t1", "Audit the routers. " + excusedLine);
        String spec = "Devices checked, then one section per device with guest isolation OK or BROKEN";
        ctx.trajectory().record(new AgentAction("plan_report", Map.of("spec", spec), "planning"),
                AgentObservation.success("plan_report", "noted", Map.of(), 5));
        ctx.addArtifact("router_audit", Map.of(), Map.of(),
                excusedLine + "\n" + spec + "\n" + secret, true,
                Artifact.labelFor(List.of("ROUTER_PASS"), List.of()));

        var config = new OwnClawConfig();
        config.getMentor().setProvider("anthropic");
        var cloud = new AssistantPartsTest.Scripted(List.of(AssistantPartsTest.call("respond", Map.of("message", "ok"))));
        var gateway = new CloudGateway(cloud, cloud, config, row -> { }, null);

        // Texts built from what a result can carry: fresh words, runs of the private result,
        // text the owner or the cloud wrote that the private result repeats, in any mix.
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
            boolean labelledPrivate = ctx.decide(List.of(), List.of(), false, output).label() == Label.PRIVATE;
            boolean refused;
            try {
                gateway.chat(List.of(LlmMessage.system("S"), LlmMessage.user(output)),
                        LlmRequestConfig.DEFAULT.withEgress(ctx.egress("think")));
                refused = false;
            } catch (EgressRefused e) {
                refused = true;
            }
            assertEquals(refused, labelledPrivate, "the label and the door disagree on: " + output);
            if (labelledPrivate) privateSeen++; else publicSeen++;
        }
        assertTrue(privateSeen > 50 && publicSeen > 50,
                "both answers were exercised: " + privateSeen + " private, " + publicSeen + " public");
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

        AgentResult r = AssistantPartsTest.loop(jdbc, registry, AssistantPartsTest.gateway(cloud, rows), config)
                .executeWithContext(ctx);

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
                .execute(DelegationBehaviourTest.plan("summarise the audit"), ctx);

        var read = ctx.artifacts().get(1);
        assertEquals(Label.PRIVATE, read.label());
        assertEquals(List.of("repeats {{1}}"), read.why());
        assertEquals(List.of("after private data in this delegation"), ctx.artifacts().get(2).why(),
                "the local model has read the audit now; what it does next can carry it");
        assertTrue(ctx.localTierReadPrivate());
    }
}
