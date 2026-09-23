package com.ownclaw.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.privacy.PrivateIndex;
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
 * fixed order — refuse if unclassified, scrub vault values, check the canary, send, record —
 * so privacy is a property of the code path. A prompt builder can be wrong about what it
 * rendered and the call is still refused; a new call site next month either carries a context
 * or does not get through.
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

    /** A vault value shorter than this is not scrubbed: it is too short to be a secret. */
    static final int MIN_SECRET_LENGTH = 8;

    private final LlmProvider anthropic;
    private final LlmProvider openai;
    private final OwnClawConfig config;
    private final EgressLedger ledger;
    private final ObjectMapper mapper;

    /** Spring constructs the providers here, and nowhere else. */
    public CloudGateway(OwnClawConfig config, ObjectMapper mapper, EgressLedger ledger) {
        this(new AnthropicProvider(config, mapper), new OpenAiProvider(config, mapper),
                config, ledger, mapper);
    }

    /** Public so a test in another package can put a fake provider behind the door. */
    public CloudGateway(LlmProvider anthropic, LlmProvider openai, OwnClawConfig config,
                        EgressLedger ledger, ObjectMapper mapper) {
        this.anthropic = anthropic;
        this.openai = openai;
        this.config = config;
        this.ledger = ledger == null ? EgressLedger.none() : ledger;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
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
                    List.of(), 0, 0, null, 0, 0, "unclassified"));
            throw new EgressRefused(providerName);
        }

        // (b) Scrub: a vault value never leaves, whatever put it in the text.
        int scrubs = 0;
        var scrubbedMessages = new ArrayList<LlmMessage>(messages.size());
        for (LlmMessage m : messages) {
            Scrubbed s = scrub(m.content(), egress.secretValues());
            scrubs += s.count();
            scrubbedMessages.add(s.count() == 0 ? m : new LlmMessage(m.role(), s.text()));
        }
        List<ToolSpec> tools = cfg.tools();
        List<ToolSpec> scrubbedTools = null;
        if (tools != null) {
            scrubbedTools = new ArrayList<>(tools.size());
            for (ToolSpec t : tools) {
                Scrubbed s = scrub(t.description(), egress.secretValues());
                scrubs += s.count();
                scrubbedTools.add(s.count() == 0 ? t
                        : new ToolSpec(t.name(), s.text(), t.inputSchema()));
            }
        }

        // (c) The canary: every part, before the socket opens.
        List<Part> parts = parts(scrubbedMessages, scrubbedTools);
        int privateArtifacts = egress.index().isEmpty() ? 0 : -1; // -1: unknown count, index non-empty
        for (Part part : parts) {
            PrivateIndex.Hit hit = egress.index().firstHitIn(part.text());
            if (hit == null) continue;
            String window = PrivateIndex.normalise(part.text())
                    .substring(hit.offset(), Math.min(hit.offset() + hit.length(),
                            PrivateIndex.normalise(part.text()).length()));
            if (egress.allowed().test(hit.handle(), window)) continue;
            String ref = "$" + hit.handle() + " in part " + part.index() + " (" + part.kind()
                    + ") at " + hit.offset();
            if (mode() == Mode.ENFORCE) {
                ledger.record(row(egress, providerName, model, EgressLedger.Decision.REFUSED,
                        parts, scrubs, privateArtifacts, null, 0, 0, ref));
                log.error("Cloud call REFUSED for task {}: {}", egress.taskId(), ref);
                throw new EgressRefused(providerName, hit.handle(), "artifact", part.index(),
                        part.kind(), hit.offset());
            }
            log.warn("Cloud call would have been refused for task {} (canary in OBSERVE): {}",
                    egress.taskId(), ref);
            ledger.record(row(egress, providerName, model, EgressLedger.Decision.OBSERVED_LEAK,
                    parts, scrubs, privateArtifacts, null, 0, 0, ref));
            break;
        }

        // (d) Send, and record what happened either way.
        LlmRequestConfig outbound = scrubbedTools == null ? cfg : cfg.withTools(scrubbedTools);
        try {
            LlmResponse response = provider.chat(scrubbedMessages, outbound);
            ledger.record(row(egress, providerName, model, EgressLedger.Decision.SENT, parts,
                    scrubs, privateArtifacts, response,
                    tools == null ? 0 : tools.size(), 0, null));
            return response;
        } catch (RuntimeException e) {
            ledger.record(row(egress, providerName, model, EgressLedger.Decision.ERROR, parts,
                    scrubs, privateArtifacts, null, tools == null ? 0 : tools.size(), 0,
                    e.getClass().getSimpleName()));
            throw e;
        }
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

    // ── scrubbing ──

    record Scrubbed(String text, int count) {}

    /**
     * Replace every occurrence of a secret vault value with {@code «vault:KEY»}. Deterministic,
     * so the Anthropic prefix stays byte-stable across steps. Values shorter than
     * {@link #MIN_SECRET_LENGTH} are left: they are too short to be secrets and long enough to
     * be words.
     */
    static Scrubbed scrub(String text, Map<String, String> secrets) {
        if (text == null || text.isEmpty() || secrets == null || secrets.isEmpty()) {
            return new Scrubbed(text, 0);
        }
        String out = text;
        int count = 0;
        for (var e : secrets.entrySet()) {
            String value = e.getValue();
            if (value == null || value.length() < MIN_SECRET_LENGTH) continue;
            int at;
            while ((at = out.indexOf(value)) >= 0) {
                out = out.substring(0, at) + "«vault:" + e.getKey() + "»" + out.substring(at + value.length());
                count++;
            }
        }
        return new Scrubbed(out, count);
    }

    // ── the row ──

    private static EgressLedger.Row row(EgressContext egress, String provider, String model,
                                        EgressLedger.Decision decision, List<Part> parts,
                                        int scrubs, int privateArtifacts, LlmResponse response,
                                        int toolCount, int unused, String refusalRef) {
        long bytes = 0;
        var ledgerParts = new ArrayList<EgressLedger.Part>(parts.size());
        for (Part p : parts) {
            EgressLedger.Part lp = p.forLedger();
            ledgerParts.add(lp);
            bytes += p.text() == null ? 0 : p.text().getBytes(StandardCharsets.UTF_8).length;
        }
        int pt = response == null ? 0 : response.promptTokens();
        int ct = response == null ? 0 : response.completionTokens();
        int cw = response == null ? 0 : response.cacheCreationTokens();
        int cr = response == null ? 0 : response.cacheReadTokens();
        double cost = response == null ? 0.0 : safeCost(model, response);
        return new EgressLedger.Row(
                egress == null ? null : egress.userId(),
                egress == null ? null : egress.taskId(),
                egress == null ? "unclassified" : egress.purpose(),
                provider, model, decision, List.copyOf(ledgerParts), bytes, toolCount,
                pt, ct, cw, cr, cost, scrubs, privateArtifacts, refusalRef);
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
