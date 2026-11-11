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
 * schemas included -- to text the code wrote itself only as far as the vault's values
 * ({@link LlmMessage#authored}, {@link ToolSpec#authored}) -- and this is the only code that
 * writes or reads a placeholder: what comes
 * back holds the real values again, and every caller sees those. A prompt builder can be wrong
 * about what it rendered and the call is still refused -- in every message part but an
 * assistant turn. Two kinds of part are not scanned by the canary (see the canary loop): an
 * assistant turn, which replays the model's earlier turns and holds nothing derived from a tool
 * result or other private input; and the tools' descriptions and schemas, the registry's text,
 * which is trusted input -- a builder that rendered a result into one would not be refused. A
 * new call site next month either carries a context or does not get through.
 * <p>
 * A reply asked for as written ({@link LlmRequestConfig#asWritten}) is handed back with its
 * placeholders, and remembered under its context ({@link EgressContext.HandedOut}); sent back as
 * the model's turn, byte for byte, it is an echo, and goes out with the vault's values removed and
 * nothing else done to it -- no identifier replaced or learned, no canary. The caller puts the
 * values back itself, once, in what it keeps ({@link #restored}).
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
        // schemas too: the registry's text is trusted by the canary, not by the filter. Text the
        // code wrote itself -- the think call's system prompt, the special actions -- holds no
        // identifier of the user's: the vault's values are removed from it, and no word of it is
        // replaced, so a word the user's table holds by coincidence no longer rewrites the
        // instructions, and the front of every cached prompt, the moment it is learned.
        String user = egress.userId();
        Map<String, String> vault = egress.secretValues();
        var tally = new Redactor.Tally();
        var filteredMessages = new ArrayList<LlmMessage>(messages.size());
        // Which messages are echoes: the model's own words coming back exactly as this gateway
        // handed them out (below).
        var echoes = new java.util.HashSet<Integer>();
        for (LlmMessage m : messages) {
            // A TOOLS message is the registry's own tool names, not data: filtered, a name that
            // looked like an identifier would no longer name its tool.
            if (m.role() == LlmMessage.Role.TOOLS) {
                filteredMessages.add(m);
                continue;
            }
            // An echo: an assistant turn whose text is one this gateway handed out under this
            // context as the model wrote it (asWritten, EgressContext.HandedOut) -- a skill's code
            // in parts, each part sent back as the model's turn before the next is asked for. It
            // holds no value of the user's: only the placeholders the model was shown, never put
            // back. The vault's values are removed from it as from every part, and nothing else
            // happens to it: no identifier is replaced and none is learned from it. Filtered, the
            // model's own literals -- its default SSID 'OpenWrt', its hostname 'gateway' -- were
            // learned as the user's identifiers for good, a value the restore had put back inside a
            // longer word went out in clear, and the next part's request no longer began with the
            // one before. The gateway checks the text itself: no caller can mark a turn as one.
            if (m.role() == LlmMessage.Role.ASSISTANT && egress.handedOut().holds(m.content())) {
                echoes.add(filteredMessages.size());
                filteredMessages.add(new LlmMessage(m.role(), Redactor.filterAuthored(m.content(), vault, tally),
                        m.authored(), m.marked()));
                continue;
            }
            if (cfg.asWritten() && m.role() == LlmMessage.Role.ASSISTANT) {
                // Every model turn of such a request is an echo; one that is not is filtered,
                // learned from and scanned like any other text (and by the canary below).
                log.warn("Cloud call for task {} ({}): an assistant turn of {} characters is not a text this "
                        + "gateway handed out as written; filtered like any other", egress.taskId(),
                        egress.purpose(), m.content() == null ? 0 : m.content().length());
            }
            // The engine's marked message is filtered as one text like any other -- its markers
            // are only where the provider splits it (LlmMessage#marked), and nothing the filter
            // removes is let off because a marker stands in it -- and goes on marked.
            String content = m.authored() ? Redactor.filterAuthored(m.content(), vault, tally)
                    : redactor.filter(user, m.content(), vault, tally);
            filteredMessages.add(new LlmMessage(m.role(), content, m.authored(), m.marked()));
        }
        List<ToolSpec> tools = cfg.tools();
        List<ToolSpec> filteredTools = null;
        if (tools != null) {
            filteredTools = new ArrayList<>(tools.size());
            for (ToolSpec t : tools) {
                @SuppressWarnings("unchecked")
                Map<String, Object> schema = (Map<String, Object>) (t.authored()
                        ? Redactor.filterAuthoredTree(t.inputSchema(), vault, tally)
                        : redactor.filterTree(user, t.inputSchema(), vault, tally));
                filteredTools.add(new ToolSpec(t.name(), t.authored()
                        ? Redactor.filterAuthored(t.description(), vault, tally)
                        : redactor.filter(user, t.description(), vault, tally),
                        schema, t.deferred(), t.authored()));
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
        // markers are, no vault value survives this point -- in any of the forms it is removed
        // in, a JSON string's included.
        for (var sv : Redactor.vaultForms(vault)) {
            for (Part part : parts) {
                int at = sv.at(part.text(), 0);
                if (at < 0) continue;
                ledger.record(row(egress, providerName, model, EgressLedger.Decision.REFUSED,
                        parts, tally, null, List.of(), 0, "vault:" + sv.key() + " survived scrubbing"));
                log.error("Cloud call REFUSED for task {}: vault value {} survived scrubbing",
                        egress.taskId(), sv.key());
                throw new EgressRefused(providerName, 0, "vault:" + sv.key(), part.index(),
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
            //
            // An echo (above) passes for a stronger reason: its text is byte for byte what the
            // provider sent this gateway, so there is nothing in it the provider has not got. In a
            // request that asked for its replies as written, every model turn should be one; one
            // that is not has no such guarantee -- the code handed it a text the gateway did not
            // hand out, a reply put back with the user's values, say -- and it is scanned like a
            // user's part. The think call's replayed actions are not echoes, and pass as before.
            if ("assistant".equals(part.kind()) && (echoes.contains(part.index()) || !cfg.asWritten())) continue;
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
                    @Override public void received(Part part, String text) { hook.received(part, text); }
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
        // Asked for as written, it is handed out as the model wrote it, and remembered as handed out
        // so under this context: the caller sends it back as the model's turn, an echo, and puts the
        // values back itself, once, in what it keeps (restored).
        if (cfg.asWritten()) {
            egress.handedOut().add(response.content());
            return response.requireComplete(providerName);
        }
        return restoredReply(user, response).requireComplete(providerName);
    }

    /**
     * Text the caller built from replies it asked for as written ({@link LlmRequestConfig#asWritten})
     * -- a skill's code, its parts joined -- with the user's values put back where it names their
     * placeholders, once: a placeholder the cut between two parts fell in is whole here, its quotes
     * with it, so it is put back as one within a reply is, between the other quotes when its value
     * holds the quote around it; and a placeholder's shape the user wrote as text, shown to the
     * model as one of its own (Redactor's TEXT), comes back as that text and is not put back again.
     */
    @Override
    public String restored(String asWritten, LlmRequestConfig cfg) {
        EgressContext egress = cfg == null ? null : cfg.egress();
        return egress == null ? asWritten : redactor.restore(egress.userId(), asWritten);
    }

    /** Whether the active provider is sent an effort at all. */
    @Override
    public boolean takesEffort() { return active().takesEffort(); }

    /** The reply with the user's placeholders put back to their values; the same reply when it names none. */
    private LlmResponse restoredReply(String user, LlmResponse r) {
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
