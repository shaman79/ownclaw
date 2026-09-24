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
     * Egress rows are grouped with the next step row: the calls that chose that step. A step row
     * holds RUNNING token totals, so a step's local tokens are the difference from the previous
     * step. The tier follows: a delegation ran on the local model; any other step was chosen by
     * the cloud if cloud calls or cloud tokens preceded it, or by the local model if only local
     * tokens rose (the fallback when the cloud is down).
     */
    static Map<String, Object> build(List<Map<String, Object>> rows) {
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
        Map<String, Object> outcome = null, answer = null;
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
                    if ("ERROR".equals(d.path("decision").asText())) costIsFloor = true;
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

                    String decidedBy = !pending.isEmpty() || cloudDelta > 0 ? "cloud"
                            : localDelta > 0 ? "local" : null;
                    String tool = d.path("tool").asText();
                    boolean reported = d.path("reportedFailure").asBoolean(false);

                    var s = new LinkedHashMap<String, Object>();
                    s.put("step", stepNo);
                    s.put("at", at);
                    s.put("tool", tool);
                    s.put("tier", "delegate".equals(tool) ? "local" : decidedBy);
                    s.put("decidedBy", decidedBy);
                    s.put("ok", d.path("success").asBoolean(false) && !reported);
                    s.put("reportedFailure", reported);
                    s.put("reason", d.hasNonNull("reason") ? d.path("reason").asText() : null);
                    s.put("durationMs", d.path("durationMs").asLong());
                    s.put("cloudCalls", pending.size());
                    s.put("cloudTokens", pending.isEmpty() ? cloudDelta : billed(pending));
                    s.put("localTokens", localDelta);
                    s.put("costUsd", pending.isEmpty() ? null : costOf(pending));
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
                        long localDelta = Math.max(0, d.path("localTokens").asLong() - prevLocal);
                        if (!pending.isEmpty() || localDelta > 0) {
                            answer = new LinkedHashMap<>();
                            answer.put("cloudCalls", pending.size());
                            answer.put("cloudTokens", billed(pending));
                            answer.put("localTokens", localDelta);
                            answer.put("costUsd", pending.isEmpty() ? null : costOf(pending));
                            answer.put("tier", pending.isEmpty() ? "local" : "cloud");
                        }
                    }
                    pending.clear();
                }
                default -> { }
            }
        }

        // The canary line for each artifact: how many later requests were checked for its text,
        // and in how many it was found. Only for a PRIVATE result the canary actually indexed and
        // long enough to look for -- anything else is shown as not checked, never as clean.
        for (int i = 0; i < artifacts.size(); i++) {
            var a = artifacts.get(i);
            boolean checkable = "PRIVATE".equals(a.get("label")) && Boolean.TRUE.equals(a.get("indexed"))
                    && ((Number) a.get("chars")).longValue() >= MIN_CHECKABLE_CHARS;
            if (!checkable) { a.put("canary", null); continue; }
            String prefix = a.get("handle") + " in part";
            int checked = 0, hits = 0;
            for (int j = 0; j < calls.size(); j++) {
                if (callIds.get(j) <= artifactIds.get(i)) continue;
                var c = calls.get(j);
                Object refusal = c.get("refusal");
                // A vault refusal happens before the canary runs, so it checked nothing.
                if ("REFUSED".equals(c.get("decision"))
                        && (refusal == null || !String.valueOf(refusal).startsWith("{{"))) continue;
                checked++;
                if (refusal != null && String.valueOf(refusal).startsWith(prefix)) hits++;
            }
            var canary = new LinkedHashMap<String, Object>();
            canary.put("checkedCalls", checked);
            canary.put("hits", hits);
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
        out.put("request", request);
        out.put("outcome", outcome);
        out.put("totals", totals);
        out.put("steps", steps);
        out.put("answer", answer);
        out.put("inProgress", outcome == null && !pending.isEmpty());
        out.put("calls", calls);
        out.put("artifacts", artifacts);
        out.put("notObserved", NOT_OBSERVED);
        return out;
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
        var c = new LinkedHashMap<String, Object>();
        c.put("at", at);
        c.put("step", null);
        c.put("purpose", d.path("purpose").asText(null));
        c.put("model", d.path("model").asText(null));
        c.put("decision", d.path("decision").asText());
        c.put("bytesOut", d.path("bytesOut").asLong());
        c.put("promptTokens", d.path("promptTokens").asLong());
        c.put("completionTokens", d.path("completionTokens").asLong());
        c.put("cacheReadTokens", d.path("cacheReadTokens").asLong());
        c.put("cacheWriteTokens", d.path("cacheWriteTokens").asLong());
        c.put("costUsd", d.path("costUsd").asDouble());
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
            t += ((Number) c.get("promptTokens")).longValue() + ((Number) c.get("completionTokens")).longValue()
                    + ((Number) c.get("cacheReadTokens")).longValue() + ((Number) c.get("cacheWriteTokens")).longValue();
        }
        return t;
    }

    private static double costOf(List<Map<String, Object>> group) {
        double t = 0;
        for (var c : group) t += ((Number) c.get("costUsd")).doubleValue();
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
