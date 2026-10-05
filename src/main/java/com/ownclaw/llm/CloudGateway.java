package com.ownclaw.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.privacy.PrivateIndex;
import com.ownclaw.privacy.Redactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The one door to a cloud model.
 * <p>
 * This is the only object in the application that can construct or call {@code AnthropicProvider}
 * or {@code OpenAiProvider}: they are not beans, their constructors are package-private, and a
 * test walks the source tree to keep it that way. Every call passes through {@link #chat} in a
 * fixed order — refuse if unclassified, filter every part ({@link Redactor}: secrets removed,
 * identifiers replaced by placeholders), check the canary, send, record, put the values back in
 * the reply, then hand back only a reply that is complete — so privacy is a property of the code
 * path. The filter applies to every part, assistant turns and the tools' descriptions and
 * schemas included, and this is the only code that writes or reads a placeholder: what comes
 * back holds the real values again, and every caller sees those. A prompt builder can be wrong
 * about what it rendered and the call is still refused -- in every message part but an
 * assistant turn. Two kinds of part are not scanned by the canary (see the canary loop): an
 * assistant turn, which replays the model's earlier turns and holds nothing derived from a tool
 * result or other private input; and the tools' descriptions and schemas, the registry's text,
 * which is trusted input -- a builder that rendered a result into one would not be refused. A
 * new call site next month either carries a context or does not get through.
 * <p>
 * Deliberately not a policy engine. There is one mode switch, {@code ENFORCE} or {@code OBSERVE},
 * and OBSERVE changes exactly one thing: a canary hit is sent and recorded as such instead of
 * refused. Everything else here is not configurable, because it is not a preference.
 */
@Component
public final class CloudGateway implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(CloudGateway.class);

    /** What the canary does on a hit. There is no OFF. */
    public enum Mode { ENFORCE, OBSERVE }

    private final LlmProvider anthropic;
    private final LlmProvider openai;
    private final OwnClawConfig config;
    private final EgressLedger ledger;
    private final ObjectMapper mapper;
    private final Redactor redactor;

    /**
     * Spring constructs the providers here, and nowhere else. Annotated because the class has a
     * second public constructor for tests, and with two candidates Spring picks neither — the
     * boot check found the application unable to start, which no unit test could have.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public CloudGateway(OwnClawConfig config, ObjectMapper mapper, EgressLedger ledger,
                        Redactor redactor) {
        this(new AnthropicProvider(config, mapper), new OpenAiProvider(config, mapper),
                config, ledger, mapper, redactor);
    }

    /** Public so a test in another package can put a fake provider behind the door. */
    public CloudGateway(LlmProvider anthropic, LlmProvider openai, OwnClawConfig config,
                        EgressLedger ledger, ObjectMapper mapper, Redactor redactor) {
        this.anthropic = anthropic;
        this.openai = openai;
        this.config = config;
        this.ledger = ledger == null ? row -> { } : ledger;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
        this.redactor = redactor;
    }

    /** {@link #CloudGateway(LlmProvider, LlmProvider, OwnClawConfig, EgressLedger, ObjectMapper,
     *  Redactor)} with the placeholder table in memory, for a test that has no database. */
    public CloudGateway(LlmProvider anthropic, LlmProvider openai, OwnClawConfig config,
                        EgressLedger ledger, ObjectMapper mapper) {
        this(anthropic, openai, config, ledger, mapper, new Redactor(null));
    }

    /**
     * The provider the configuration names — read on every call, so the setup wizard and the
     * settings page changing it takes effect without anything else having to notice.
     */
    LlmProvider active() {
        return "anthropic".equalsIgnoreCase(config.getMentor().getProvider()) ? anthropic : openai;
    }

    private Mode mode() {
        var p = config.getPrivacy();
        return p == null || p.getCanary() == null ? Mode.ENFORCE : p.getCanary();
    }

    @Override public String name() { return active().name(); }
    @Override public String model() { return active().model(); }
    @Override public boolean supportsTools() { return active().supportsTools(); }
    @Override public boolean isAvailable() { return active().isAvailable(); }

    @Override
    public LlmResponse chat(List<LlmMessage> messages, LlmRequestConfig cfg) {
        LlmProvider provider = active();
        String providerName = provider.name();
        String model = provider.model();
        EgressContext egress = cfg == null ? null : cfg.egress();

        // (a) Unclassified means denied.
        if (egress == null) {
            ledger.record(row(null, providerName, model, EgressLedger.Decision.REFUSED,
                    List.of(), new Redactor.Tally(), null, List.of(), 0, "unclassified"));
            throw new EgressRefused(providerName);
        }

        // (b) The filter, on every part: a vault value or a secret found by code never leaves,
        // and an identifier leaves as its placeholder (Redactor). The tools' descriptions and
        // schemas too: the registry's text is trusted by the canary, not by the filter.
        String user = egress.userId();
        Map<String, String> vault = egress.secretValues();
        var tally = new Redactor.Tally();
        var filteredMessages = new ArrayList<LlmMessage>(messages.size());
        for (LlmMessage m : messages) {
            // A TOOLS message is the registry's own tool names, not data: filtered, a name that
            // looked like an identifier would no longer name its tool.
            filteredMessages.add(m.role() == LlmMessage.Role.TOOLS ? m
                    : new LlmMessage(m.role(), redactor.filter(user, m.content(), vault, tally)));
        }
        List<ToolSpec> tools = cfg.tools();
        List<ToolSpec> filteredTools = null;
        if (tools != null) {
            filteredTools = new ArrayList<>(tools.size());
            for (ToolSpec t : tools) {
                @SuppressWarnings("unchecked")
                Map<String, Object> schema = (Map<String, Object>) redactor.filterTree(user,
                        t.inputSchema(), vault, tally);
                filteredTools.add(new ToolSpec(t.name(), redactor.filter(user, t.description(), vault, tally),
                        schema, t.deferred()));
            }
        }

        // (c) The canary, before the socket opens, over the text as filtered -- the last line for
        // a PRIVATE result's bytes: every part but the replayed assistant turns and the
        // registry's tool and schema parts.
        String observed = null;
        List<Part> parts = parts(filteredMessages, filteredTools);
        // A tool description or schema is authored by the cloud at skill_create or by the
        // owner, and it was in the prompt on every step before the artifact existed -- so a run
        // of it matching a later result is a collision, not a disclosure. Skills routinely
        // describe the shape of their own output, and without this the first call after such a
        // skill ran was refused, after the side effect had happened.
        //
        // THE ALLOWANCE IS FOR THESE PARTS. Two attempts widened it to the registry's text
        // wherever that text appeared, to spare a skill catalogue that the prompt rendered
        // twice; both leaked. A substring test excused any short artifact quoted anywhere as
        // soon as its bytes turned up in some skill's example, and length-limiting the test
        // only moved the boundary to 32 characters and then refused every short artifact's
        // legitimate self-description -- which deadlocks a task from that step on. The
        // duplicate render is fixed where it is made (ThinkingEngine renders the catalogue
        // into the user message only when the tools array is not carrying it), so the door
        // does not have to reason about text that appears in two places at once.
        //
        // Known limit, and it is the trust boundary rather than a bug: if a prompt builder ever
        // renders artifact content INTO a tool description, this excuses it. The registry is
        // trusted input here; the canary's promise covers the message parts the model did not
        // write (see the assistant clause below).
        // The filter's post-condition for vault values, checked rather than trusted, on every
        // part -- assistant parts included -- by the rule the filter removes them by
        // (Redactor.vaultValueAt). A marker is built from the key name and a value can be a
        // substring of its own replacement, so the fallback marker was itself unverified -- a
        // value of "redacted" would have been written out inside «vault:redacted». Whatever the
        // markers are, no vault value survives this point.
        for (var sv : vault.entrySet()) {
            for (Part part : parts) {
                int at = Redactor.vaultValueAt(part.text(), sv.getValue(), 0);
                if (at < 0) continue;
                ledger.record(row(egress, providerName, model, EgressLedger.Decision.REFUSED,
                        parts, tally, null, List.of(), 0, "vault:" + sv.getKey() + " survived scrubbing"));
                log.error("Cloud call REFUSED for task {}: vault value {} survived scrubbing",
                        egress.taskId(), sv.getKey());
                throw new EgressRefused(providerName, 0, "vault:" + sv.getKey(), part.index(),
                        part.kind(), at);
            }
        }

        for (Part part : parts) {
            // An assistant part is the model's own earlier output, replayed: the actions it chose
            // (ThinkingEngine renders them as JSON). A reply that could not be used is quoted in
            // a user part instead, and scanned there, as is the attempt a code repair quotes.
            // The model wrote it in answer to requests that passed this loop, and it is only ever
            // shown descriptors of PRIVATE results, so nothing in it is new to the cloud.
            // Scanned, it was refused whenever a private result repeated the model's own words
            // the way JSON prints them: a task ended on a skill spec the cloud had written at its
            // first step, because a later private result quoted the report headings that spec
            // had dictated. The allowance for the cloud's own words could not excuse it -- it
            // compares each argument as typed, and the replay is JSON, so a window that starts at
            // a quote or spans an escape never matches.
            //
            // Sound only while nothing derived from a tool result or other private input is put
            // into an assistant message: the model's output, and the one action the loop writes
            // itself under a tool's name -- the skill_create CapabilityResolver builds at step 1
            // from its own constants, which holds no result either (AgentTrajectory.Turn#byTheLoop).
            // AssistantPartsTest pins that through the real renderer: a replay carries a reference
            // as the model wrote it, never the bytes it resolves to. The vault check above still
            // covers these parts.
            if ("assistant".equals(part.kind())) continue;
            // The registry's own parts: excused, as the allowance at the top of (c) says.
            if (part.kind().startsWith("tool:") || part.kind().startsWith("schema:")) continue;
            // Every run in the part is tried, not only the first (PrivateIndex.firstLeakIn). It is
            // the question AgentContext.decide asks of each result before labelling it, over the
            // same index and the same excuses: a result whose own facts say PUBLIC but whose
            // bytes repeat a private one is labelled PRIVATE there and reaches this part as a
            // description, instead of ending the task here one step later.
            // (AgentContext.firstLeakIn says where the two can still come apart.)
            PrivateIndex.Hit hit = egress.index().firstLeakIn(part.text(), egress.allowed());
            if (hit != null) {
                String ref = "{{" + hit.handle() + "}} in part " + part.index() + " (" + part.kind()
                        + ") at " + hit.offset();
                if (mode() == Mode.ENFORCE) {
                    ledger.record(row(egress, providerName, model, EgressLedger.Decision.REFUSED,
                            parts, tally, null, List.of(), 0, ref));
                    log.error("Cloud call REFUSED for task {}: {}", egress.taskId(), ref);
                    throw new EgressRefused(providerName, hit.handle(),
                            egress.toolOf().apply(hit.handle()), part.index(), part.kind(), hit.offset());
                }
                // OBSERVE: remember it and go on to send. The row is written once, after the
                // call, so it carries the tokens and the cost like any other -- two rows for one
                // call made the ledger's own count say a call had been made twice.
                log.warn("Cloud call would have been refused for task {} (canary in OBSERVE): {}",
                        egress.taskId(), ref);
                observed = ref;
                break;
            }
        }

        // (d) Send, and record what happened either way. The caller's hook goes with the request,
        // and what it is told of attempts that ended without a reply is kept here too: they were
        // billed, no reply carries their counts, and they belong on this call's row.
        var billedWithoutReply = new ArrayList<LlmResponse.Usage>();
        LlmProgress hook = cfg.progress();
        LlmRequestConfig outbound = (filteredTools == null ? cfg : cfg.withTools(filteredTools))
                .withProgress(new LlmProgress() {
                    @Override public void onProgress() { hook.onProgress(); }
                    @Override public void calling(Runnable cancel) { hook.calling(cancel); }
                    @Override public void billed(LlmResponse.Usage usage) {
                        billedWithoutReply.add(usage);
                        hook.billed(usage);
                    }
                });
        LlmResponse response;
        try {
            response = provider.chat(filteredMessages, outbound);
        } catch (RuntimeException e) {
            // The leak record survives a failing call. Consolidating to one row moved it after
            // the send, so a provider error used to discard it: the bytes had gone out and the
            // only note that they should not have went with the exception.
            ledger.record(row(egress, providerName, model, EgressLedger.Decision.ERROR, parts,
                    tally, null, billedWithoutReply, tools == null ? 0 : tools.size(),
                    observed == null ? e.getClass().getSimpleName()
                            : observed + " (call then failed: " + e.getClass().getSimpleName() + ")"));
            throw e;
        }
        // Recorded under the model that wrote the reply and priced at its rates: a declined
        // request can be answered by Anthropic's fallback model.
        ledger.record(row(egress, providerName, response.model() != null ? response.model() : model,
                observed == null ? EgressLedger.Decision.SENT : EgressLedger.Decision.OBSERVED_LEAK,
                parts, tally, response, billedWithoutReply, tools == null ? 0 : tools.size(), observed));
        // (e) The values back where the reply names their placeholders, in its text and in every
        // tool call's arguments: from here on nothing sees a placeholder. Then the check, so a
        // refused or cut-off reply is on the ledger with its tokens and why it ended before its
        // caller is told it is no answer -- and what it carries is restored like any reply.
        return restored(user, response).requireComplete(providerName);
    }

    /** The reply with the user's placeholders put back to their values; the same reply when it names none. */
    private LlmResponse restored(String user, LlmResponse r) {
        String content = redactor.restore(user, r.content());
        String invalid = redactor.restore(user, r.invalidToolCall());
        boolean changed = content != r.content() || invalid != r.invalidToolCall();
        var calls = new ArrayList<ToolCall>(r.toolCalls().size());
        for (ToolCall c : r.toolCalls()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = (Map<String, Object>) redactor.restoreTree(user, c.arguments());
            changed |= args != c.arguments();
            calls.add(args == c.arguments() ? c : new ToolCall(c.id(), c.name(), args));
        }
        return !changed ? r : new LlmResponse(content, calls, invalid, r.stopReason(), r.stopDetail(),
                r.model(), r.maxOutputTokens(), r.contextWindow(), r.usage());
    }

    // ── parts ──

    /** One piece of the outbound body, with what the ledger may keep of it. */
    record Part(int index, String kind, String text) {
        EgressLedger.Part forLedger() {
            return new EgressLedger.Part(index, kind, text == null ? 0 : text.length(),
                    sha256_16(text));
        }
    }

    private List<Part> parts(List<LlmMessage> messages, List<ToolSpec> tools) {
        var out = new ArrayList<Part>();
        int i = 0;
        for (LlmMessage m : messages) {
            out.add(new Part(i++, m.role().name().toLowerCase(Locale.ROOT), m.content()));
        }
        if (tools != null) {
            for (ToolSpec t : tools) {
                out.add(new Part(i++, "tool:" + t.name(), t.description()));
                try {
                    out.add(new Part(i++, "schema:" + t.name(),
                            mapper.writeValueAsString(t.inputSchema())));
                } catch (Exception e) {
                    out.add(new Part(i++, "schema:" + t.name(), String.valueOf(t.inputSchema())));
                }
            }
        }
        return out;
    }

    // ── the row ──

    /**
     * @param response           the reply, or null when none came
     * @param billedWithoutReply what the call's attempts that ended without a reply were billed
     *                           for ({@link LlmProgress#billed}); with the reply's own, the row's
     *                           tokens and cost
     */
    private static EgressLedger.Row row(EgressContext egress, String provider, String model,
                                        EgressLedger.Decision decision, List<Part> parts,
                                        Redactor.Tally tally, LlmResponse response,
                                        List<LlmResponse.Usage> billedWithoutReply, int toolCount,
                                        String refusalRef) {
        long bytes = 0;
        var ledgerParts = new ArrayList<EgressLedger.Part>(parts.size());
        for (Part p : parts) {
            EgressLedger.Part lp = p.forLedger();
            ledgerParts.add(lp);
            bytes += p.text() == null ? 0 : p.text().getBytes(StandardCharsets.UTF_8).length;
        }
        var usage = new ArrayList<>(billedWithoutReply);
        if (response != null) usage.addAll(response.usage());
        LlmResponse billed = LlmResponse.billedFor(usage);
        int pt = billed.promptTokens();
        int ct = billed.completionTokens();
        int cw = billed.cacheCreationTokens();
        int cr = billed.cacheReadTokens();
        double cost = safeCost(model, billed);
        return new EgressLedger.Row(
                egress == null ? null : egress.userId(),
                egress == null ? null : egress.taskId(),
                egress == null ? "unclassified" : egress.purpose(),
                provider, model, decision, List.copyOf(ledgerParts),
                // Bytes that LEFT. A refused call sent none, and counting its payload as egress
                // is the one arithmetic error that would make the ledger overstate exposure.
                decision == EgressLedger.Decision.REFUSED ? 0 : bytes,
                toolCount, pt, ct, cw, cr, cost, tally.secretsRemoved(), tally.identifiersReplaced(),
                refusalRef,
                response == null ? null : response.stopDescription());
    }

    private static double safeCost(String model, LlmResponse response) {
        try {
            return ModelPricing.costUsd(model, response);
        } catch (Exception e) {
            return 0.0;
        }
    }

    public static String sha256_16(String text) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 8);
        } catch (Exception e) {
            return "";
        }
    }
}
