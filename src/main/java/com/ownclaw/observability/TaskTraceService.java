package com.ownclaw.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One task, as its owner may see it: the steps it took, which model ran each, what it cost, and
 * what went to the cloud model -- with every private result named as kept back.
 * <p>
 * Built only from the task's rows in {@code events}, read through
 * {@link EventLogService#taskEvents}, which is scoped to the user: the task id is eight hex
 * characters, so the user id is the only thing keeping one account out of another's tasks. The
 * ops API has richer views of the same rows, but they are token-gated and not scoped to a user,
 * so nothing here reuses them.
 * <p>
 * What it does not contain, on purpose: the text of any request (never stored), the per-part list
 * and hashes of a request (noise in a UI, and a hash of a private part can confirm a guess), and
 * any tool's error output beyond the excerpt a failed step's row already carries.
 */
@Service
public class TaskTraceService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What the page cannot see, said plainly. Always shown. */
    static final List<String> NOT_OBSERVED = List.of(
            "Only requests from OwnClaw to the cloud model are recorded. A skill with network "
                    + "access can send data elsewhere; that is not recorded.",
            "The check looks for runs of 32 or more characters of private text (or whole values "
                    + "of 8 or more). A paraphrase, or a short value such as a PIN, is not caught.",
            "Private text the cloud already had from elsewhere (your own message, a public "
                    + "result, a tool's description) is not counted as found.",
            "The local model's server is assumed to be private; nothing checks that.");

    /** The canary cannot look for anything shorter (PrivateIndex's minimum). */
    private static final int MIN_CHECKABLE_CHARS = 8;

    private final EventLogService eventLog;

    public TaskTraceService(EventLogService eventLog) {
        this.eventLog = eventLog;
    }

    /** The task's trace, or empty when this user has no rows for it. */
    public Optional<Map<String, Object>> trace(String userId, String taskId) {
        var rows = eventLog.taskEvents(userId, taskId);
        if (rows.isEmpty()) return Optional.empty();
        var trace = new LinkedHashMap<String, Object>();
        trace.put("taskId", taskId);
        trace.putAll(build(rows));
        return Optional.of(trace);
    }

    /**
     * The whole page, from the task's rows in id order. Timestamps have one-second resolution, so
     * id order is the only order that keeps a step next to the cloud calls that led to it.
     * <p>
     * Egress rows are grouped with the next step row: the calls made on the way to that step.
     * Only a "think" call that was answered chose it; the others wrote code (codegen) or read a skill
     * (analyze) for it. A step row holds RUNNING token totals, so its local tokens are the
     * difference from the previous step. A delegation ran on the local model only if local
     * tokens rose. A task from before requests were recorded has no egress rows at all; then the
     * cloud chose a step if its cloud tokens rose.
     */
    static Map<String, Object> build(List<Map<String, Object>> rows) {
        // Requests are recorded from the day step rows began carrying reportedFailure, so a task
        // with such a row and no egress rows made no cloud request -- which is worth saying.
        boolean recorded = rows.stream().anyMatch(r -> "egress".equals(r.get("event_type"))
                || "step".equals(r.get("event_type")) && String.valueOf(r.get("details")).contains("\"reportedFailure\""));
        var steps = new ArrayList<Map<String, Object>>();
        var calls = new ArrayList<Map<String, Object>>();
        var callIds = new ArrayList<Long>();
        var artifacts = new ArrayList<Map<String, Object>>();
        var artifactIds = new ArrayList<Long>();
        var pending = new ArrayList<Map<String, Object>>();
        var decisions = new LinkedHashMap<String, Integer>();
        long bytesOut = 0, prompt = 0, completion = 0, cacheRead = 0, cacheWrite = 0;
        int scrubs = 0;
        double cost = 0;
        boolean costIsFloor = false;
        long prevCloud = 0, prevLocal = 0;
        Map<String, Object> outcome = null;
        String request = null;

        for (var row : rows) {
            String type = String.valueOf(row.get("event_type"));
            long id = ((Number) row.get("id")).longValue();
            String at = row.get("timestamp") == null ? null : String.valueOf(row.get("timestamp"));
            JsonNode d = parse(row.get("details"));

            switch (type) {
                case "egress" -> {
                    if (d == null || !d.has("decision")) {
                        decisions.merge("unparsed", 1, Integer::sum);
                        continue;
                    }
                    var c = call(d, at);
                    calls.add(c);
                    callIds.add(id);
                    pending.add(c);
                    decisions.merge(d.path("decision").asText(), 1, Integer::sum);
                    bytesOut += d.path("bytesOut").asLong();
                    prompt += d.path("promptTokens").asLong();
                    completion += d.path("completionTokens").asLong();
                    cacheRead += d.path("cacheReadTokens").asLong();
                    cacheWrite += d.path("cacheWriteTokens").asLong();
                    scrubs += d.path("scrubs").asInt();
                    cost += d.path("costUsd").asDouble();
                    // A failed call is recorded with no tokens and no cost, so a total over it
                    // is a lower bound.
                    if (isError(c)) costIsFloor = true;
                }
                case "step" -> {
                    if (d == null) continue;
                    long cumCloud = d.path("cloudTokens").asLong(), cumLocal = d.path("localTokens").asLong();
                    long cloudDelta = Math.max(0, cumCloud - prevCloud);
                    long localDelta = Math.max(0, cumLocal - prevLocal);
                    prevCloud = Math.max(prevCloud, cumCloud);
                    prevLocal = Math.max(prevLocal, cumLocal);
                    int stepNo = d.path("step").asInt();
                    for (var c : pending) c.put("step", stepNo);

                    boolean cloudChose = recorded
                            ? pending.stream().anyMatch(c -> "think".equals(c.get("purpose")) && answered(c))
                            : cloudDelta > 0;
                    String decidedBy = cloudChose ? "cloud" : localDelta > 0 ? "local" : null;
                    String tool = d.path("tool").asText();
                    // Absent on rows written before it was recorded: unknown, not "no".
                    Boolean reported = d.has("reportedFailure") ? d.path("reportedFailure").asBoolean() : null;

                    var s = new LinkedHashMap<String, Object>();
                    s.put("step", stepNo);
                    s.put("at", at);
                    s.put("tool", tool);
                    s.put("tier", "delegate".equals(tool) ? (localDelta > 0 ? "local" : null) : decidedBy);
                    s.put("decidedBy", decidedBy);
                    s.put("ok", d.path("success").asBoolean(false) && !Boolean.TRUE.equals(reported));
                    s.put("reportedFailure", reported);
                    s.put("reason", d.hasNonNull("reason") ? d.path("reason").asText() : null);
                    s.put("durationMs", d.path("durationMs").asLong());
                    s.put("cloudCalls", recorded ? pending.size() : null);
                    s.put("cloudTokens", pending.isEmpty() ? cloudDelta : billed(pending));
                    s.put("localTokens", localDelta);
                    s.put("costUsd", pending.isEmpty() ? null : costOf(pending));
                    s.put("costIsFloor", pending.stream().anyMatch(TaskTraceService::isError));
                    var produced = stepArtifacts(d);
                    s.put("artifacts", produced);
                    for (var a : produced) { artifacts.add(new LinkedHashMap<>(a)); artifactIds.add(id); }
                    steps.add(s);
                    pending.clear();
                }
                case "attachment" -> {
                    if (d == null || !d.has("artifact")) continue;
                    artifacts.add(artifact(d.path("artifact").asText(), d));
                    artifactIds.add(id);
                }
                case "task_completed" -> {
                    request = row.get("summary") == null ? null : String.valueOf(row.get("summary"));
                    if (d != null) {
                        outcome = new LinkedHashMap<>();
                        outcome.put("reason", d.path("reason").asText(null));
                        outcome.put("steps", d.path("steps").asInt());
                        outcome.put("durationMs", d.path("durationMs").asLong());
                        outcome.put("cloudTokens", d.path("cloudTokens").asLong());
                        outcome.put("localTokens", d.path("localTokens").asLong());
                        outcome.put("at", at);
                    }
                    pending.clear();
                }
                default -> { }
            }
        }

        // For each artifact: how many later requests left at all, and -- for a PRIVATE result
        // the canary could look for -- how many of those were checked for its text, in how many
        // it was found, and of those how many went out anyway (the check was only observing) or
        // failed (whether they reached the provider is not recorded); and how many later
        // requests were never checked for it. Anything else is shown as not checked, never as
        // clean.
        for (int i = 0; i < artifacts.size(); i++) {
            var a = artifacts.get(i);
            String prefix = a.get("handle") + " in part";
            int after = 0, checked = 0, hits = 0, leaked = 0, failed = 0, unchecked = 0;
            for (int j = 0; j < calls.size(); j++) {
                if (callIds.get(j) <= artifactIds.get(i)) continue;
                var c = calls.get(j);
                boolean refused = "REFUSED".equals(c.get("decision"));
                if (!refused) after++;
                String refusal = c.get("refusal") == null ? null : String.valueOf(c.get("refusal"));
                boolean named = refusal != null && refusal.startsWith("{{");
                // A vault refusal happens before the canary runs, so it checked nothing.
                if (refused && !named) continue;
                // The canary stops at its first hit, and the record names only that one; any
                // other private result in the same request was not looked for. If that request
                // went out anyway, this one's text may have gone with it.
                if (named && !refusal.startsWith(prefix)) {
                    if (!refused) unchecked++;
                    continue;
                }
                checked++;
                if (named) {
                    hits++;
                    if ("ERROR".equals(c.get("decision"))) failed++;
                    else if (!refused) leaked++;
                }
            }
            a.put("requestsAfter", after);
            // Indexed, or found anyway: rows from before `indexed` was recorded still have hits.
            boolean checkable = "PRIVATE".equals(a.get("label"))
                    && ((Number) a.get("chars")).longValue() >= MIN_CHECKABLE_CHARS
                    && (Boolean.TRUE.equals(a.get("indexed")) || hits > 0);
            if (!checkable) { a.put("canary", null); continue; }
            var canary = new LinkedHashMap<String, Object>();
            canary.put("checkedCalls", checked);
            canary.put("hits", hits);
            canary.put("leaked", leaked);
            canary.put("failed", failed);
            canary.put("unchecked", unchecked);
            a.put("canary", canary);
        }

        var totals = new LinkedHashMap<String, Object>();
        totals.put("calls", calls.size());
        totals.put("bytesOut", bytesOut);
        totals.put("promptTokens", prompt);
        totals.put("completionTokens", completion);
        totals.put("cacheReadTokens", cacheRead);
        totals.put("cacheWriteTokens", cacheWrite);
        totals.put("scrubs", scrubs);
        totals.put("costUsd", cost);
        totals.put("costIsFloor", costIsFloor);
        totals.put("decisions", decisions);

        var out = new LinkedHashMap<String, Object>();
        out.put("recorded", recorded);
        out.put("request", request);
        out.put("outcome", outcome);
        out.put("totals", totals);
        out.put("steps", steps);
        out.put("calls", calls);
        out.put("artifacts", artifacts);
        out.put("notObserved", NOT_OBSERVED);
        return out;
    }

    /** Whether a request came back with a reply: a refused one never left, a failed one got none. */
    private static boolean answered(Map<String, Object> c) {
        return "SENT".equals(c.get("decision")) || "OBSERVED_LEAK".equals(c.get("decision"));
    }

    private static boolean isError(Map<String, Object> c) {
        return "ERROR".equals(c.get("decision"));
    }

    private static Map<String, Object> call(JsonNode d, String at) {
        int msgCount = 0, toolCount = 0;
        long msgChars = 0, toolChars = 0;
        for (JsonNode p : d.path("parts")) {
            String kind = p.path("kind").asText();
            long chars = p.path("chars").asLong();
            if (kind.startsWith("tool:")) { toolCount++; toolChars += chars; }
            else if (kind.startsWith("schema:")) toolChars += chars;
            else { msgCount++; msgChars += chars; }
        }
        // A call that failed has no response, so no tokens and no cost: not recorded, not zero.
        boolean error = "ERROR".equals(d.path("decision").asText());
        var c = new LinkedHashMap<String, Object>();
        c.put("at", at);
        c.put("step", null);
        c.put("purpose", d.path("purpose").asText(null));
        c.put("model", d.path("model").asText(null));
        c.put("decision", d.path("decision").asText());
        c.put("bytesOut", d.path("bytesOut").asLong());
        c.put("promptTokens", error ? null : d.path("promptTokens").asLong());
        c.put("completionTokens", error ? null : d.path("completionTokens").asLong());
        c.put("cacheReadTokens", error ? null : d.path("cacheReadTokens").asLong());
        c.put("cacheWriteTokens", error ? null : d.path("cacheWriteTokens").asLong());
        c.put("costUsd", error ? null : d.path("costUsd").asDouble());
        c.put("scrubs", d.path("scrubs").asInt());
        c.put("refusal", d.hasNonNull("refusal") ? d.path("refusal").asText() : null);
        c.put("messages", Map.of("count", msgCount, "chars", msgChars));
        c.put("tools", Map.of("count", toolCount, "chars", toolChars));
        return c;
    }

    private static List<Map<String, Object>> stepArtifacts(JsonNode d) {
        var out = new ArrayList<Map<String, Object>>();
        if (d.has("artifact")) out.add(artifact(d.path("artifact").asText(), d));
        for (JsonNode x : d.path("artifacts")) {
            out.add(artifact("{{" + x.path("n").asInt() + "}}", x));
        }
        return out;
    }

    private static Map<String, Object> artifact(String handle, JsonNode d) {
        var a = new LinkedHashMap<String, Object>();
        a.put("handle", handle);
        a.put("tool", d.path("tool").asText(null));
        a.put("label", d.path("label").asText(null));
        a.put("chars", d.path("chars").asLong());
        var why = new ArrayList<String>();
        for (JsonNode w : d.path("why")) why.add(w.asText());
        a.put("why", why);
        a.put("indexed", d.has("indexed") ? d.path("indexed").asBoolean() : null);
        return a;
    }

    /** Tokens billed for a group of calls: in, out, and both cache directions. */
    private static long billed(List<Map<String, Object>> group) {
        long t = 0;
        for (var c : group) {
            for (String k : List.of("promptTokens", "completionTokens", "cacheReadTokens", "cacheWriteTokens")) {
                if (c.get(k) instanceof Number n) t += n.longValue();
            }
        }
        return t;
    }

    private static double costOf(List<Map<String, Object>> group) {
        double t = 0;
        for (var c : group) if (c.get("costUsd") instanceof Number n) t += n.doubleValue();
        return t;
    }

    private static JsonNode parse(Object details) {
        if (details == null) return null;
        try {
            JsonNode n = JSON.readTree(String.valueOf(details));
            return n != null && n.isObject() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }
}
