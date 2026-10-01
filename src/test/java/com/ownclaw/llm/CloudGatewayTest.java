package com.ownclaw.llm;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.privacy.PrivateIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The door, in order: refuse if unclassified, scrub, canary, send, record.
 * <p>
 * Each test names the step it pins and the mutation that would break it. The fake provider
 * records exactly what it was handed, because "the provider never saw it" is the claim.
 */
class CloudGatewayTest {

    /** A provider that records what it receives and answers with a fixed response. */
    static final class Recording implements LlmProvider {
        final List<List<LlmMessage>> calls = new ArrayList<>();
        final List<LlmRequestConfig> configs = new ArrayList<>();
        RuntimeException failWith;
        /** Tell the request's progress hook about an event before answering, as a stream does. */
        boolean streams;
        /** Tell the hook of an attempt that ended without a reply before answering or failing. */
        LlmResponse.Usage billsFirst;
        String name = "anthropic";
        String model = "claude-opus-5";
        public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
            calls.add(m); configs.add(c);
            if (billsFirst != null) c.progress().billed(billsFirst);
            if (failWith != null) throw failWith;
            if (streams) c.progress().onProgress();
            return Replies.of("ok", 120, 7, 30, 90, "end_turn");
        }
        public boolean isAvailable() { return true; }
        public boolean supportsTools() { return true; }
        public String name() { return name; }
        public String model() { return model; }
    }

    static final class Rows implements EgressLedger {
        final List<Row> rows = new ArrayList<>();
        public void record(Row r) { rows.add(r); }
        Row last() { return rows.get(rows.size() - 1); }
    }

    private static String prose(int chars, long seed) {
        var r = new Random(seed);
        var sb = new StringBuilder();
        String[] w = {"invoice", "Novák", "platba", "quarterly", "Bratčice", "ledger", "due"};
        while (sb.length() < chars) sb.append(w[r.nextInt(w.length)]).append(r.nextInt(9999)).append(' ');
        return sb.substring(0, chars);
    }

    private static OwnClawConfig config(String provider, CloudGateway.Mode mode) {
        var c = new OwnClawConfig();
        c.getMentor().setProvider(provider);
        c.getPrivacy().setCanary(mode);
        return c;
    }

    private static EgressContext egress(PrivateIndex index, Map<String, String> secrets,
                                        java.util.function.BiPredicate<Integer, String> allowed) {
        return new EgressContext("u1", "t1", "think", index, secrets, allowed, null);
    }

    private static List<LlmMessage> messages(String... contents) {
        var out = new ArrayList<LlmMessage>();
        out.add(LlmMessage.system("SYSTEM"));
        for (String c : contents) out.add(LlmMessage.user(c));
        return out;
    }

    @Test
    @DisplayName("public messages with a context arrive unchanged, and one SENT row is written")
    void publicCallIsSentAndRecorded() {
        var provider = new Recording(); var rows = new Rows();
        var gw = new CloudGateway(provider, new Recording(), config("anthropic", CloudGateway.Mode.ENFORCE), rows, null);

        var cfg = new LlmRequestConfig(null, null, false).withEgress(egress(new PrivateIndex(), Map.of(), (h, w) -> false));
        var resp = gw.chat(messages("What is the capital of France?"), cfg);

        assertEquals("ok", resp.content());
        assertEquals(1, provider.calls.size());
        assertEquals("What is the capital of France?", provider.calls.get(0).get(1).content());
        assertEquals(1, rows.rows.size());
        var row = rows.last();
        assertEquals(EgressLedger.Decision.SENT, row.decision());
        assertEquals("think", row.purpose());
        assertEquals("t1", row.taskId());
        assertEquals(2, row.parts().size(), "system + user");
        assertEquals(30, row.parts().get(1).chars());
        assertEquals(16, row.parts().get(1).sha256_16().length(), "a hash prefix, never content");
        assertEquals(120, row.promptTokens());
        assertEquals(90, row.cacheReadTokens());
        assertTrue(row.costUsd() >= 0.0);
        // Mutation: drop the ledger write -> rows empty.
    }

    @Test
    @DisplayName("no context: the provider is never called, the call is refused, a REFUSED row names why")
    void unclassifiedIsRefused() {
        var provider = new Recording(); var rows = new Rows();
        var gw = new CloudGateway(provider, new Recording(), config("anthropic", CloudGateway.Mode.ENFORCE), rows, null);

        assertThrows(EgressRefused.class, () -> gw.chat(messages("hello"), new LlmRequestConfig(null, null, false)));
        assertTrue(provider.calls.isEmpty(), "unclassified means denied, not scanned against nothing");
        assertEquals(EgressLedger.Decision.REFUSED, rows.last().decision());
        assertEquals("unclassified", rows.last().refusalRef());
        // Mutation: treat a null context as allowed -> provider called.
    }

    @Test
    @DisplayName("a canary hit in a message refuses the call before the provider is called")
    void canaryHitRefuses() {
        var provider = new Recording(); var rows = new Rows();
        var gw = new CloudGateway(provider, new Recording(), config("anthropic", CloudGateway.Mode.ENFORCE), rows, null);
        String secret = prose(5_000, 1);
        var index = new PrivateIndex(); index.addPrivate(3, secret);

        var cfg = new LlmRequestConfig(null, null, false).withEgress(egress(index, Map.of(), (h, w) -> false));
        var ex = assertThrows(EgressRefused.class,
                () -> gw.chat(messages("Here is what the mailbox held: " + secret.substring(1000, 1040)), cfg));

        assertTrue(provider.calls.isEmpty(), "the whole point: checked before the socket opens");
        assertEquals(3, ex.handle());
        assertEquals(1, ex.partIndex(), "the user turn, not the system prompt");
        assertEquals(EgressLedger.Decision.REFUSED, rows.last().decision());
        assertTrue(rows.last().refusalRef().startsWith("{{3}} in part 1"));
        // Mutation: scan after the call -> provider.calls not empty.
    }

    @Test
    @DisplayName("a tool description that describes its own output is not a leak of that output")
    void registryTextIsNotALeak() {
        // A skill's description is authored by the cloud at skill_create and was in the prompt
        // on every step before the skill had ever run, so a run of it matching that skill's
        // later output is a collision, not a disclosure — and skills routinely say what they
        // return. Without this, every call after such a skill ran was refused, after the side
        // effect had happened.
        var provider = new Recording(); var rows = new Rows();
        var gw = new CloudGateway(provider, new Recording(), config("anthropic", CloudGateway.Mode.ENFORCE), rows, null);
        String output = prose(5_000, 2);
        var index = new PrivateIndex(); index.addPrivate(4, output);
        var tool = new ToolSpec("smtp_send_email", "Sends mail. Returns: " + output.substring(200, 240),
                Map.of("type", "object"));

        var cfg = new LlmRequestConfig(null, null, false).withTools(List.of(tool))
                .withEgress(egress(index, Map.of(), (h, w) -> false));
        gw.chat(messages("send it"), cfg);

        assertEquals(1, provider.calls.size());
        assertEquals(EgressLedger.Decision.SENT, rows.last().decision());
    }

    @Test
    @DisplayName("the allowance is for registry parts; a message quoting the same run is refused")
    void theAllowanceIsForRegistryPartsOnly() {
        // A skill's description is authored at skill_create and was in the prompt on every step
        // before the skill had ever run, so a run of it matching that skill's later output is a
        // collision. Without this, the first call after such a skill ran was refused — after the
        // side effect had happened.
        //
        // Two attempts widened this to "the registry's text, wherever it appears", to spare a
        // catalogue the prompt rendered twice. Both leaked: a substring test excused any short
        // artifact quoted anywhere, and length-limiting it moved the hole to exactly 32
        // characters. The duplicate render is fixed where it is made; the door stays narrow.
        var provider = new Recording(); var rows = new Rows();
        var gw = new CloudGateway(provider, new Recording(), config("anthropic", CloudGateway.Mode.ENFORCE), rows, null);
        String output = prose(5_000, 2);
        var index = new PrivateIndex(); index.addPrivate(4, output);
        String described = output.substring(200, 240);
        var tool = new ToolSpec("smtp_send_email", "Sends mail. Returns: " + described,
                Map.of("type", "object"));
        var cfg = new LlmRequestConfig(null, null, false).withTools(List.of(tool))
                .withEgress(egress(index, Map.of(), (h, w) -> false));

        gw.chat(messages("send the invoice"), cfg);
        assertEquals(1, provider.calls.size(),
                "the collision is inside the tool description the cloud authored; refusing it "
                        + "would kill every call from the moment that skill first ran");

        var ex = assertThrows(EgressRefused.class,
                () -> gw.chat(messages("the mailbox said: " + described), cfg));
        assertEquals(1, provider.calls.size(), "refused before the socket opens");
        assertEquals(4, ex.handle());
        assertEquals("user", rows.last().refusalRef().contains("(user)") ? "user" : "?",
                "a MESSAGE quoting the same run is written after the result existed: " 
                        + rows.last().refusalRef());
    }

    @Test
    @DisplayName("a SHORT private artifact is never waved through by a registry collision")
    void aShortArtifactIsNotExcusedByTheRegistry() {
        // The allowance exists for a 32-character run that a skill's own description happens to
        // reproduce. An artifact of 8..31 characters is registered WHOLE, so for one of those
        // the window IS the entire artifact, and a plain substring test against the catalogue
        // waved the whole thing through in every part -- an account number that appears as an
        // example in some unrelated schema, quoted back in a message, went from REFUSED to SENT.
        var provider = new Recording(); var rows = new Rows();
        var gw = new CloudGateway(provider, new Recording(), config("anthropic", CloudGateway.Mode.ENFORCE), rows, null);
        String iban = "CZ4720100123";
        var index = new PrivateIndex(); index.addPrivate(4, iban);
        var tool = new ToolSpec("fx_rates", "Rates. Example account: " + iban,
                Map.of("type", "object"));
        var cfg = new LlmRequestConfig(null, null, false).withTools(List.of(tool))
                .withEgress(egress(index, Map.of(), (h, w) -> false));

        var ex = assertThrows(EgressRefused.class,
                () -> gw.chat(messages("the statement said the account is " + iban), cfg));
        assertTrue(provider.calls.isEmpty(), "refused before the socket opens");
        assertEquals(4, ex.handle());
        assertEquals(EgressLedger.Decision.REFUSED, rows.last().decision());
        // Mutation: drop the hit.length() >= WINDOW condition -> SENT, the whole artifact out.
    }

    @Test
    @DisplayName("a skill describing its own SHORT output does not deadlock the task")
    void aShortSelfDescriptionIsNotARefusal() {
        // smtp_send_email's description says it returns {"sent": true}; the step then records
        // exactly that as a PRIVATE artifact. The bytes are in the tools array of every single
        // request from then on, so a door that refuses them refuses every remaining call in the
        // task — either no email at all, or the email went and the owner is told it did not.
        var provider = new Recording(); var rows = new Rows();
        var gw = new CloudGateway(provider, new Recording(), config("anthropic", CloudGateway.Mode.ENFORCE), rows, null);
        String shortOutput = "{\"sent\": true, \"id\": \"m-8891\"}";
        var index = new PrivateIndex(); index.addPrivate(2, shortOutput);
        var tool = new ToolSpec("smtp_send_email",
                "Sends mail. Returns " + shortOutput + " on success.", Map.of("type", "object"));
        var cfg = new LlmRequestConfig(null, null, false).withTools(List.of(tool))
                .withEgress(egress(index, Map.of(), (h, w) -> false));

        assertDoesNotThrow(() -> gw.chat(messages("send it"), cfg),
                "the hit is inside the tool part the cloud wrote and is receiving anyway");
        assertEquals(EgressLedger.Decision.SENT, rows.last().decision());

        // ...but the same short artifact quoted back in a MESSAGE is still the artifact.
        assertThrows(EgressRefused.class,
                () -> gw.chat(messages("the skill returned " + shortOutput), cfg));
    }

    @Test
    @DisplayName("a vault value scrubbing could not reach refuses the call")
    void secretsThatSurviveScrubbingAreRefused() {
        // scrub() rewrites message text and tool DESCRIPTIONS. A schema is neither, so a secret
        // sitting in one used to travel with a scrub count of zero and nothing to show for it.
        // The marker itself was also unverified: a value of "redacted" would have been written
        // out inside «vault:redacted». This is the post-condition rather than the promise.
        var provider = new Recording(); var rows = new Rows();
        var gw = new CloudGateway(provider, new Recording(), config("anthropic", CloudGateway.Mode.ENFORCE), rows, null);
        var tool = new ToolSpec("fx_rates", "Rates.",
                Map.of("type", "object", "example", "token=hunter2secret"));
        var cfg = new LlmRequestConfig(null, null, false).withTools(List.of(tool))
                .withEgress(egress(new PrivateIndex(), Map.of("API_TOKEN", "hunter2secret"),
                        (h, w) -> false));

        assertThrows(EgressRefused.class, () -> gw.chat(messages("go"), cfg));
        assertTrue(provider.calls.isEmpty(), "checked before the socket opens");
        assertEquals(EgressLedger.Decision.REFUSED, rows.last().decision());
        assertTrue(rows.last().refusalRef().contains("API_TOKEN"), rows.last().refusalRef());
    }

    @Test
    @DisplayName("in OBSERVE, a call that then fails still records what was observed")
    void observedLeakSurvivesAProviderFailure() {
        // Consolidating the ledger to one row moved it after the send, and a provider error
        // then discarded it: the bytes had gone out and the only note saying they should not
        // have went with the exception. OBSERVE exists to measure exactly those calls.
        var provider = new Recording(); var rows = new Rows();
        provider.failWith = new IllegalStateException("socket reset");
        var gw = new CloudGateway(provider, new Recording(),
                config("anthropic", CloudGateway.Mode.OBSERVE), rows, null);
        String output = prose(5_000, 7);
        var index = new PrivateIndex(); index.addPrivate(4, output);
        var cfg = new LlmRequestConfig(null, null, false).withEgress(egress(index, Map.of(), (h, w) -> false));

        assertThrows(IllegalStateException.class,
                () -> gw.chat(messages("the mailbox said: " + output.substring(900, 1200)), cfg));

        var row = rows.last();
        assertEquals(EgressLedger.Decision.ERROR, row.decision());
        assertTrue(row.refusalRef().startsWith("{{4}}"), row.refusalRef());
        assertTrue(row.refusalRef().contains("IllegalStateException"), row.refusalRef());
        // Mutation: write only the exception name -> the observation is lost and OBSERVE
        // undercounts precisely the calls that failed.
    }

    @Test
    @DisplayName("every hit in a part is checked, not only the first")
    void allHitsInAPartAreChecked() {
        // A private confirmation opens with the public digest it sent and continues with the
        // address and the message id. Allowing the first run must not end the scan.
        var provider = new Recording(); var rows = new Rows();
        var gw = new CloudGateway(provider, new Recording(), config("anthropic", CloudGateway.Mode.ENFORCE), rows, null);
        String publicPart = prose(500, 21), privatePart = prose(500, 22);
        var index = new PrivateIndex();
        index.addPrivate(2, publicPart + " " + privatePart);

        // The first run is allowed (it is the public digest); the tail is not.
        String allowed = PrivateIndex.normalise(publicPart);
        var cfg = new LlmRequestConfig(null, null, false).withEgress(egress(index, Map.of(),
                (h, w) -> allowed.contains(w)));

        var ex = assertThrows(EgressRefused.class,
                () -> gw.chat(messages(publicPart + " " + privatePart), cfg));
        assertTrue(provider.calls.isEmpty());
        assertEquals(2, ex.handle());
        // Mutation: continue to the next PART on an allowed hit -> sent.
    }

    @Test
    @DisplayName("a private tail shorter than a window, after an allowed run, is refused")
    void aShortPrivateTailIsRefused() {
        // The same confirmation with only its address after the digest: 26 characters, less than
        // a window, carried by the windows that take in the end of the digest.
        var provider = new Recording(); var rows = new Rows();
        var gw = new CloudGateway(provider, new Recording(), config("anthropic", CloudGateway.Mode.ENFORCE), rows, null);
        String digest = prose(130, 23);
        var index = new PrivateIndex();
        index.addPrivate(2, digest + " sent to owner@example.org");
        String allowed = PrivateIndex.normalise(digest);
        var cfg = new LlmRequestConfig(null, null, false).withEgress(egress(index, Map.of(),
                (h, w) -> allowed.contains(w)));

        var ex = assertThrows(EgressRefused.class,
                () -> gw.chat(messages(digest + " sent to owner@example.org"), cfg));
        assertEquals(2, ex.handle());
        assertTrue(provider.calls.isEmpty());
        gw.chat(messages(digest), cfg);
        assertEquals(1, provider.calls.size(), "the digest alone is sent");
    }

    @Test
    @DisplayName("a hit the task has allowed is sent")
    void allowedHitIsSent() {
        var provider = new Recording(); var rows = new Rows();
        var gw = new CloudGateway(provider, new Recording(), config("anthropic", CloudGateway.Mode.ENFORCE), rows, null);
        String secret = prose(5_000, 3);
        var index = new PrivateIndex(); index.addPrivate(2, secret);

        var cfg = new LlmRequestConfig(null, null, false).withEgress(egress(index, Map.of(), (h, w) -> h == 2));
        gw.chat(messages("quoting the task text: " + secret.substring(0, 40)), cfg);

        assertEquals(1, provider.calls.size());
        assertEquals(EgressLedger.Decision.SENT, rows.last().decision());
        // Mutation: ignore the predicate -> refused.
    }

    @Test
    @DisplayName("a secret vault value is scrubbed to its key; a non-secret value is left")
    void secretsAreScrubbed() {
        var provider = new Recording(); var rows = new Rows();
        var gw = new CloudGateway(provider, new Recording(), config("anthropic", CloudGateway.Mode.ENFORCE), rows, null);

        var cfg = new LlmRequestConfig(null, null, false).withEgress(egress(new PrivateIndex(),
                Map.of("IMAP_PASS", "hunter2secret", "SMTP_PORT", "465"), (h, w) -> false));
        gw.chat(messages("log in with hunter2secret on port 465 then hunter2secret again"), cfg);

        String sent = provider.calls.get(0).get(1).content();
        assertEquals("log in with «vault:IMAP_PASS» on port 465 then «vault:IMAP_PASS» again", sent);
        assertEquals(2, rows.last().scrubs());
        // Mutations: skip the scrub -> value sent; scrub every key -> "465" replaced.
    }

    @Test
    @DisplayName("a secret that is a substring of its own marker does not hang the call")
    void scrubTerminates() {
        var provider = new Recording(); var rows = new Rows();
        var gw = new CloudGateway(provider, new Recording(), config("anthropic", CloudGateway.Mode.ENFORCE), rows, null);
        // "vault:PASS" appears inside «vault:PASS», so restarting the search from zero rewrote
        // its own replacement for ever, holding the task's only worker thread.
        var cfg = new LlmRequestConfig(null, null, false).withEgress(egress(new PrivateIndex(),
                Map.of("PASS", "vault:PASS"), (h, w) -> false));

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
                () -> gw.chat(messages("the value is vault:PASS here"), cfg));
        // ...and the marker must not carry the secret out either. «vault:PASS» contains the
        // literal value "vault:PASS", so scrubbing it into its own key name would have sent the
        // secret in the shape of a redaction -- one scrub recorded, nothing actually withheld.
        assertEquals("the value is «vault:redacted» here", provider.calls.get(0).get(1).content());
        assertEquals(1, rows.last().scrubs());
    }

    @Test
    @DisplayName("a refused call reports zero bytes out: nothing left")
    void refusedBytesAreNotCountedAsEgress() {
        var rows = new Rows();
        var gw = new CloudGateway(new Recording(), new Recording(),
                config("anthropic", CloudGateway.Mode.ENFORCE), rows, null);
        String secret = prose(5_000, 31);
        var index = new PrivateIndex(); index.addPrivate(1, secret);

        assertThrows(EgressRefused.class, () -> gw.chat(messages("leak: " + secret.substring(0, 40)),
                new LlmRequestConfig(null, null, false).withEgress(egress(index, Map.of(), (h, w) -> false))));

        assertEquals(0, rows.last().bytesOut(),
                "counting a refused payload as egress is the one arithmetic error that would "
                        + "make the ledger overstate what left the machine");
    }

    @Test
    @DisplayName("a provider failure is recorded as ERROR and rethrown")
    void providerErrorIsRecorded() {
        var provider = new Recording(); var rows = new Rows();
        provider.failWith = new LlmException("anthropic", "429 rate limited", 429, null);
        var gw = new CloudGateway(provider, new Recording(), config("anthropic", CloudGateway.Mode.ENFORCE), rows, null);

        var cfg = new LlmRequestConfig(null, null, false).withEgress(egress(new PrivateIndex(), Map.of(), (h, w) -> false));
        assertThrows(LlmException.class, () -> gw.chat(messages("hi"), cfg));
        assertEquals(EgressLedger.Decision.ERROR, rows.last().decision());
        assertEquals("LlmException", rows.last().refusalRef());
        // Mutation: write the row only on success -> no ERROR row.
    }

    @Test
    @DisplayName("in OBSERVE a hit is sent and recorded as OBSERVED_LEAK — the only thing the switch changes")
    void observeModeSendsAndRecords() {
        var provider = new Recording(); var rows = new Rows();
        var gw = new CloudGateway(provider, new Recording(), config("anthropic", CloudGateway.Mode.OBSERVE), rows, null);
        String secret = prose(5_000, 4);
        var index = new PrivateIndex(); index.addPrivate(5, secret);

        var cfg = new LlmRequestConfig(null, null, false).withEgress(egress(index, Map.of(), (h, w) -> false));
        gw.chat(messages("leak: " + secret.substring(300, 340)), cfg);

        assertEquals(1, provider.calls.size());
        assertEquals(List.of(EgressLedger.Decision.OBSERVED_LEAK),
                rows.rows.stream().map(EgressLedger.Row::decision).toList(),
                "one row per call: a second row made the ledger's own count say a call had "
                        + "been made twice");
        assertTrue(rows.last().promptTokens() > 0,
                "and it is written after the send, so it carries the tokens like any other");
        assertNotNull(rows.last().refusalRef(), "naming what would have been refused");
        // Mutation: treat OBSERVE as silent -> decision is SENT.
    }

    @Test
    @DisplayName("the progress hook reaches the provider, with tools or without, and what it throws comes back unchanged")
    void theProgressHookGoesThroughTheDoor() {
        // Stop and the stall watchdog end an in-flight cloud call through this hook, and every
        // cloud call goes through the door.
        var provider = new Recording(); provider.streams = true; var rows = new Rows();
        var gw = new CloudGateway(provider, new Recording(), config("anthropic", CloudGateway.Mode.ENFORCE), rows, null);
        var stop = new IllegalStateException("stopped by the owner");
        var handed = new ArrayList<Runnable>();
        LlmProgress hook = new LlmProgress() {
            @Override public void onProgress() { throw stop; }
            @Override public void calling(Runnable cancel) { handed.add(cancel); }
        };
        var cfg = new LlmRequestConfig(null, null, false).withEgress(egress(new PrivateIndex(), Map.of(), (h, w) -> false))
                .withProgress(hook);

        assertSame(stop, assertThrows(IllegalStateException.class, () -> gw.chat(messages("hi"), cfg)),
                "not wrapped, not replaced");
        Runnable cancel = () -> { };
        provider.configs.get(0).progress().calling(cancel);
        assertEquals(List.of(cancel), handed, "the caller's hook is handed the cancel of what the call waits on");
        assertEquals(EgressLedger.Decision.ERROR, rows.last().decision());

        var withTools = cfg.withTools(List.of(new ToolSpec("fx_rates", "Rates.", Map.of("type", "object"))));
        assertSame(stop, assertThrows(IllegalStateException.class, () -> gw.chat(messages("hi"), withTools)),
                "the request the door rebuilds around the scrubbed tools keeps the hook");
        provider.configs.get(1).progress().calling(cancel);
        assertEquals(List.of(cancel, cancel), handed);
        // Mutation: send the request without its hook -> the provider has none to tell, and the
        // call cannot be stopped.
    }

    @Test
    @DisplayName("a call that ends without a reply is on its row with what it was billed, and the caller's hook is told of it")
    void aCallWithoutAReplyIsRecordedWithItsTokens() {
        var provider = new Recording(); var rows = new Rows();
        var gw = new CloudGateway(provider, new Recording(), config("anthropic", CloudGateway.Mode.ENFORCE), rows, null);
        var billed = new ArrayList<LlmResponse.Usage>();
        var stop = new IllegalStateException("stopped by the owner");
        var cfg = new LlmRequestConfig(null, null, false).withEgress(egress(new PrivateIndex(), Map.of(), (h, w) -> false))
                .withProgress(new LlmProgress() {
                    @Override public void onProgress() { }
                    @Override public void billed(LlmResponse.Usage usage) { billed.add(usage); }
                });
        // A reply stopped part-way: message_start had said 120,000 input tokens and 80,000 read
        // from the cache.
        var partWay = new LlmResponse.Usage("claude-opus-5", 120_000, 1, 0, 80_000);
        provider.billsFirst = partWay;
        provider.failWith = stop;

        assertSame(stop, assertThrows(IllegalStateException.class, () -> gw.chat(messages("hi"), cfg)));

        var row = rows.last();
        assertEquals(EgressLedger.Decision.ERROR, row.decision());
        assertEquals(120_000, row.promptTokens());
        assertEquals(80_000, row.cacheReadTokens());
        assertEquals(1, row.completionTokens());
        assertEquals(ModelPricing.costUsd("claude-opus-5", new LlmResponse.Usage(null, 120_000, 1, 0, 80_000)),
                row.costUsd(), 1e-9);
        assertTrue(row.costUsd() > 0.6, "priced as claude-opus-5 input: " + row.costUsd());
        assertEquals(List.of(partWay), billed, "and the task is told, so it counts them");
        // Mutation: record the ERROR row with no tokens -> 0 and $0, as before.

        // An attempt that ended without a reply, then one that answered: the row is the call's,
        // both of them.
        provider.failWith = null;
        gw.chat(messages("hi"), cfg);
        assertEquals(EgressLedger.Decision.SENT, rows.last().decision());
        assertEquals(120_000 + 120, rows.last().promptTokens());
        assertEquals(80_000 + 90, rows.last().cacheReadTokens());
        assertEquals(1 + 7, rows.last().completionTokens());
        assertEquals(List.of(partWay, partWay), billed, "the reply's own counts are not reported: the caller counts them from it");
    }

    @Test
    @DisplayName("name() and model() follow the configured provider when it changes after construction")
    void followsConfiguration() {
        var anthropic = new Recording();
        var openai = new Recording(); openai.name = "openai"; openai.model = "gpt-5.2";
        var config = config("anthropic", CloudGateway.Mode.ENFORCE);
        var gw = new CloudGateway(anthropic, openai, config, new Rows(), null);

        assertEquals("anthropic", gw.name());
        config.getMentor().setProvider("openai");
        assertEquals("openai", gw.name());
        assertEquals("gpt-5.2", gw.model());
        // Mutation: cache at construction -> still "anthropic".
    }

    @Test
    @DisplayName("the row holds sizes and hashes, never the text")
    void rowHoldsNoContent() {
        var provider = new Recording(); var rows = new Rows();
        var gw = new CloudGateway(provider, new Recording(), config("anthropic", CloudGateway.Mode.ENFORCE), rows, null);
        String text = "the quick brown fox and a long line of ordinary text that must not be stored";

        gw.chat(messages(text), new LlmRequestConfig(null, null, false).withEgress(egress(new PrivateIndex(), Map.of(), (h, w) -> false)));

        String serialised = rows.last().toString();
        assertFalse(serialised.contains("quick brown fox"), "a ledger is not an audit copy");
        assertTrue(serialised.contains("chars=" + text.length()));
    }
}
