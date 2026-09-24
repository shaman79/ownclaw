package com.ownclaw.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolving the references in a tool call — the one function both paths call.
 * <p>
 * Three pieces of code used to share this job: one substituted, one decided the label by
 * parsing the arguments again, one refused dangling references by parsing the substituted
 * values a third time. Every disagreement between them was a defect, and there were many. Here
 * the substitution happens once, and what it pulled in is returned as {@link Resolved#used}:
 * the label is computed from that list, so it describes what actually moved and cannot
 * disagree with it. The refusal is part of the same pass, over the values as the model WROTE
 * them — never over substituted content, which may legitimately begin with anything.
 */
final class References {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private References() {}

    /**
     * The outcome of resolving one call.
     *
     * @param params  the arguments with every reference replaced by the bytes it names
     * @param used    the results those bytes came from — what the label is decided from
     * @param refused the parameter that could not be resolved, or null when the call may run
     * @param reason  why, in words the model can act on; null when nothing was refused
     */
    record Resolved(Map<String, Object> params, List<Artifact> used, String refused,
                    String reason) {
        boolean ok() {
            return refused == null;
        }
    }

    /**
     * Resolve {@code written} against {@code namespace}, where {@code {{n}}} is the n-th
     * element.
     * <p>
     * Every WHOLE value a model plausibly meant as a reference is either substituted or refused;
     * none is passed on as literal text, which is how an email whose whole body was "$1.body" went
     * out and was recorded as a success. Refused, specifically:
     * a handle beyond the list; a field the result does not have; a reference to a result that
     * FAILED (by {@link Artifact#succeeded}, which also reads an {@code "ok": false} envelope),
     * whose output is an error message and never what anyone meant to send; a malformed
     * reference; and one nested in a list or an object, which cannot be substituted. A reference
     * inside other text is not recognised at all — it is text, and so is ordinary code or a
     * template that happens to contain braces and a digit.
     */
    static Resolved resolve(Map<String, Object> written, List<Artifact> namespace) {
        if (written == null || written.isEmpty()) return new Resolved(Map.of(), List.of(), null, null);
        var out = new LinkedHashMap<String, Object>(written);
        var used = new ArrayList<Artifact>();
        for (var e : out.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s) {
                ArtifactRef ref = ArtifactRef.parse(s);
                if (ref == null) {
                    if (ArtifactRef.looksLikeReference(s)) {
                        return refuse(written, e.getKey(), "'" + s.strip() + "' looks like a "
                                + "reference but is not a valid one. Write exactly {{N}} or "
                                + "{{N.field}}, with N one of the numbers below.");
                    }
                    continue;
                }
                if (ref.handle() > namespace.size()) {
                    return refuse(written, e.getKey(), ref + " refers to a result that does not "
                            + "exist.");
                }
                Artifact a = namespace.get(ref.handle() - 1);
                if (!a.succeeded()) {
                    return refuse(written, e.getKey(), ref + " is a FAILED result — its output "
                            + "is an error message, not something to pass on.");
                }
                String value = ref.field() == null ? a.output() : field(a.output(), ref.field());
                if (value == null) {
                    return refuse(written, e.getKey(), ref + " names a field that result does "
                            + "not have.");
                }
                e.setValue(value);
                if (!used.contains(a)) used.add(a);
            } else if (v instanceof Map<?, ?> || v instanceof Collection<?>) {
                String nested = nestedReference(v);
                if (nested != null) {
                    return refuse(written, e.getKey(), "'" + nested + "' is inside a list or an "
                            + "object. A reference only works as the whole value of a top-level "
                            + "parameter.");
                }
            }
        }
        return new Resolved(out, List.copyOf(used), null, null);
    }

    /** The results that can be referenced, so a refusal is actionable rather than just a no. */
    static String available(List<Artifact> namespace) {
        if (namespace.isEmpty()) {
            return "Nothing has produced a result yet, so there is nothing to reference.";
        }
        var sb = new StringBuilder("Results you can reference: ");
        for (int i = 0; i < namespace.size(); i++) {
            if (i > 0) sb.append("; ");
            Artifact a = namespace.get(i);
            sb.append(ArtifactRef.handle(i + 1)).append(" = ").append(a.tool())
              .append(a.succeeded() ? " (ok" : " (FAILED — not referenceable");
            if (a.succeeded()) {
                List<String> fields = Artifact.jsonFieldNames(a.output());
                if (!fields.isEmpty()) sb.append("; fields: ").append(String.join(", ", fields));
            }
            sb.append(')');
        }
        return sb.append(". Use one exactly, e.g. {{1}} or {{1.body_text}}, as the whole value.")
                .toString();
    }

    /**
     * The reason alone. The list of what can be referenced is appended by the caller that may
     * see it: the local model gets full field names, the cloud does not. A refusal on the cloud
     * path once listed every field name of every result, PRIVATE ones included and uncut, which
     * disclosed names under 32 characters and tripped the canary on longer ones.
     */
    private static Resolved refuse(Map<String, Object> written, String param, String why) {
        return new Resolved(written, List.of(), param, why);
    }

    /**
     * Rewrite a delegation's own references ({@code {{1}}} = its first step) into the task's
     * handles, for text the CLOUD will read — a failed step's arguments, the local summary, a
     * usage row. The two numberings meet in that text, and a cloud that copied a local
     * {{1.body_text}} into its own call would have resolved it task-wide: a different result,
     * possibly a private one from another delegation.
     */
    static Object toTaskHandles(Object value, List<Artifact> mine) {
        if (value instanceof String s) {
            var m = ArtifactRef.TOKEN.matcher(s);
            var sb = new StringBuilder();
            while (m.find()) {
                ArtifactRef ref = ArtifactRef.parse(m.group());
                String out = m.group();
                if (ref != null && ref.handle() <= mine.size()) {
                    out = new ArtifactRef(mine.get(ref.handle() - 1).n(), ref.field()).toString();
                }
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(out));
            }
            m.appendTail(sb);
            return sb.toString();
        }
        if (value instanceof Map<?, ?> map) {
            var out = new LinkedHashMap<Object, Object>();
            map.forEach((k, v) -> out.put(k, toTaskHandles(v, mine)));
            return out;
        }
        if (value instanceof Collection<?> c) {
            var out = new ArrayList<Object>();
            for (Object v : c) out.add(toTaskHandles(v, mine));
            return out;
        }
        return value;
    }

    /** One field of a JSON result, or null. A name the descriptor cut short matches by prefix. */
    private static String field(String output, String field) {
        try {
            JsonNode node = MAPPER.readTree(output);
            if (node == null || !node.isObject()) return null;
            JsonNode value = node.get(field);
            // Only for a name the descriptor visibly cut. On every miss it turned a refusal into
            // a silent wrong answer: "body" matched body_html, "o" matched ok, and the owner got
            // an email whose whole body was "true".
            if (value == null && field.endsWith("…")) value = byPrefix(node, field);
            if (value == null || value.isNull()) return null;
            return value.isTextual() ? value.asText() : value.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * The field whose name the descriptor abbreviated, matched by its visible prefix — only
     * when exactly one key matches. Two would be a guess, and a guess here picks somebody's
     * data. The cut itself cannot go: a 32-character field name would be a window of the
     * private text, so the descriptor would leak and then refuse the call carrying it.
     */
    private static JsonNode byPrefix(JsonNode node, String field) {
        String prefix = field.substring(0, field.length() - 1);
        if (prefix.isBlank()) return null;
        JsonNode found = null;
        var names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!name.startsWith(prefix)) continue;
            if (found != null) return null;
            found = node.get(name);
        }
        return found;
    }

    /** The first reference-shaped string anywhere inside a list or an object, or null. */
    private static String nestedReference(Object v) {
        if (v instanceof String s) return ArtifactRef.looksLikeReference(s) ? s.strip() : null;
        if (v instanceof Map<?, ?> m) {
            for (Object x : m.values()) {
                String r = nestedReference(x);
                if (r != null) return r;
            }
        } else if (v instanceof Collection<?> c) {
            for (Object x : c) {
                String r = nestedReference(x);
                if (r != null) return r;
            }
        }
        return null;
    }
}
