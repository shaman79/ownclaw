package com.ownclaw.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.privacy.Label;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One tool result, as the task keeps it: the bytes, and what may be said about them.
 * <p>
 * Every result a task produces — by the cloud directly, or by the local model inside a
 * delegation — becomes one of these, numbered {@code {{1}}, {{2}}, ...} for the whole task. The bytes
 * stay here, in memory, for the task's lifetime. What enters the trajectory, the chat rows and
 * every cloud prompt is decided ONCE, at record time, by {@link #asObservation}: a PUBLIC result
 * goes in as it is, exactly as today; a PRIVATE one goes in as its {@link #describe descriptor}
 * — the handle, the tool, the label and why, the size and shape, and the value of any top-level
 * boolean so that {@code ok=false} is never hidden inside an envelope. Nothing
 * downstream needs to know about labels, because nothing downstream ever holds the bytes.
 * <p>
 * The label comes from facts the code already has: in {@link #labelFor}, the skill declared
 * credentials or the call pulled in a PRIVATE result; and inside a delegation, a step that
 * came after one of those (see {@code LocalExecutor}). Not from a model, and not from a rule
 * list — the moment the label needs a taxonomy of tool names, this design has failed.
 *
 * @param n        the task-wide handle number; {@code {{n}}} in every cloud prompt and ledger
 * @param tool     the tool or skill that produced it
 * @param written  the arguments exactly as the model typed them — references intact
 * @param resolved the arguments after reference substitution, which is what actually ran;
 *                 the guards compare these, and only {@code written} is ever printed
 * @param output   the bytes
 * @param success  whether the tool reported success
 * @param label    whether the bytes may leave this machine
 * @param why      which facts made it PRIVATE, for the descriptor and the ledger
 * @param indexed  whether a PRIVATE result's bytes are in the canary's index. False for one that
 *                 is PRIVATE only because of when it was made (after a delegation read private
 *                 data) or because it was derived from such a result: withheld all the same, but
 *                 indexing it is what made the cloud's own later fetch of the same public page
 *                 trip the canary and end a run whose email had already gone.
 */
public record Artifact(int n, String tool, Map<String, Object> written,
                       Map<String, Object> resolved, String output, boolean success,
                       Label label, List<String> why, boolean indexed) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public Artifact {
        // Not Map.copyOf: it rejects a null VALUE, and a tool call carrying one is ordinary —
        // every provider keeps a JSON null as a null entry, and a local model routinely emits
        // "cc": null for an unset optional. The old StepResult never copied, so this record
        // introduced a crash that fired AFTER the tool had run: the email went out and the task
        // died with "Internal error", trajectory empty.
        written = written == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(written));
        resolved = resolved == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(resolved));
        output = output == null ? "" : output;
        label = label == null ? Label.PUBLIC : label;
        why = why == null ? List.of() : List.copyOf(why);
    }

    /** An artifact whose bytes, if PRIVATE, are indexed — every kind except the ones above. */
    public Artifact(int n, String tool, Map<String, Object> written, Map<String, Object> resolved,
                    String output, boolean success, Label label, List<String> why) {
        this(n, tool, written, resolved, output, success, label, why, true);
    }

    /** A PUBLIC artifact with no handle yet — the shape a test or a legacy caller builds. */
    public Artifact(String tool, Map<String, Object> params, String output, boolean success) {
        this(0, tool, params, params, output, success, Label.PUBLIC, List.of());
    }

    /**
     * {@code {{3}}} — the task-wide name, which is what the cloud, the ledger and the ops page
     * see. The local model never sees it: inside a delegation results are numbered from
     * {@code {{1}}} again, counting only that delegation's own steps.
     */
    public String handle() {
        return ArtifactRef.handle(n);
    }

    public boolean isPrivate() {
        return label == Label.PRIVATE;
    }

    /**
     * The label from the call's own facts — two of them, and nothing a model decides: the skill
     * declared credentials, or the call pulled in a PRIVATE result. (Inside a delegation there is
     * a third, about timing: after the local model has read private data, everything it does is
     * PRIVATE. That one is applied by {@code AgentContext.decide}, which both paths call.)
     *
     * @param requiredCredentials what the skill declared; non-empty means it reached something
     *                            that needed a secret, and its output is that something
     * @param used                the results this call's arguments actually pulled in, as the
     *                            resolver substituted them — so the label describes what moved
     *                            rather than re-reading the arguments and guessing
     */
    public static Decision labelFor(List<String> requiredCredentials, List<Artifact> used) {
        var why = new ArrayList<String>();
        if (requiredCredentials != null && !requiredCredentials.isEmpty()) {
            // The COUNT, not the names. A vault miss is worded "Missing required credentials:
            // SMTP_PASS, SMTP_USER" by the skill harness, so naming them here put a 40-character
            // run of the output into the descriptor -- and the descriptor is what the cloud
            // reads, so the canary refused the call that carried it and the run died instead of
            // saying "credentials missing". The key names are already in the tool schema the
            // cloud holds, and the raw text is in the skill_usage row the owner reads.
            why.add("credentials (" + requiredCredentials.size() + ")");
        }
        // Files are not weighed here. Every skill run in a task holding one is given it, whether
        // or not the call references it, so the rule belongs to the task rather than to the
        // call: AgentContext.decide applies it, on both paths.
        if (used != null) {
            for (Artifact a : used) {
                if (a.isPrivate()) why.add("references " + a.handle());
            }
        }
        return new Decision(why.isEmpty() ? Label.PUBLIC : Label.PRIVATE, List.copyOf(why));
    }

    /**
     * A label, the facts that produced it, and whether the canary should index the bytes.
     * <p>
     * Not indexed when the result is PRIVATE only because of WHEN it was produced — after a
     * delegation read private data — rather than because of what it is. Such a result is withheld
     * from the cloud like any other PRIVATE one; but indexing it is what made the cloud's own later
     * fetch of the same public page trip the canary and end a run whose email had already gone.
     */
    public record Decision(Label label, List<String> why, boolean indexed) {
        public Decision(Label label, List<String> why) {
            this(label, why, true);
        }
    }

    /**
     * Whether the tool did what it was asked, not merely whether its process exited cleanly.
     * <p>
     * A skill reports failure two ways: the harness's success flag, or an envelope with
     * {@code "ok": false} inside — which is how the production smtp_send_email reports every SMTP
     * error. Trusting the flag alone told the model a send that never happened had succeeded, and
     * the "never twice" guard then refused the retry that would have delivered it.
     */
    public boolean succeeded() {
        if (!success) return false;
        if (output == null) return true;
        String t = output.strip();
        if (!(t.startsWith("{") && t.endsWith("}"))) return true;
        try {
            // The whole top level: when the descriptor listed only the first twelve keys, an "ok"
            // in thirteenth place was read as success. A string "false" counts too; some skills
            // write one.
            JsonNode node = MAPPER.readTree(t);
            if (node == null || !node.isObject()) return true;
            for (String key : List.of("ok", "success")) {
                JsonNode v = node.get(key);
                if (v != null && (v.isBoolean() ? !v.asBoolean() : "false".equalsIgnoreCase(v.asText()))) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * What may be said about a PRIVATE artifact: everything except its content.
     * <p>
     * Every top-level field of a JSON result is listed, and offered as a reference. Top-level
     * booleans are shown WITH their values. A success envelope around a failure —
     * {@code {"ok": false, "error": "..."}} — is the normal shape of a skill result, and a
     * descriptor that hid {@code ok=false} would have the cloud report a send that never
     * happened. Everything else is shown as a kind and a size: a string is where the data is,
     * and a number can BE the data — a balance, a count of unread messages. A field's name is
     * shown only when it is shorter than a canary window; a longer one is named by its position
     * ({@link ArtifactRef#toField}).
     */
    public String describe() {
        var sb = new StringBuilder(handle()).append(' ').append(tool)
                .append(succeeded() ? " ✓" : " ✗").append(" — ").append(label);
        if (!why.isEmpty()) sb.append(" (").append(String.join("; ", why)).append(')');
        Shape shape = shapeOf(n, output);
        sb.append(" · ").append(shape.kind()).append(" · ")
          .append(String.format("%,d", output.length())).append(" chars");
        if (isPrivate() && !indexed) {
            // A result hidden because of WHEN it was made came from a tool fed by what the model
            // typed after reading private data. Its key names and booleans are not a skill's
            // schema but possibly that data -- a key-value store or a listing keyed by its input
            // echoes it straight into a field name. So: that it happened, and how big.
            return sb.toString();
        }
        if (!success && !shape.isJson()) {
            sb.append(" (text withheld; skill_usage row via ops)");
        }
        for (var e : shape.primitives().entrySet()) {
            sb.append(" · ").append(e.getKey()).append('=').append(e.getValue());
        }
        if (!shape.fields().isEmpty()) {
            sb.append(" · fields: ").append(String.join(", ", shape.fields()));
        }
        // How to USE it, as COMPLETE tokens. Without this the descriptor is a dead end: the cloud
        // is shown that 4,210 characters of menu exist and told no way to put them in an email,
        // so it writes the email from the description and the owner gets a confident message
        // with no menu in it. A template ("pass <handle>.<field>") was worse, beside a field list
        // that annotates its names: the cloud composed "body_text (string, 48 chars)" into the
        // reference, which resolved to nothing, and the literal went out as the body. So: the
        // exact strings, in one list, one for every field. The substitution happens here, so
        // naming the handle discloses nothing.
        // Not for a result that failed: the resolver refuses it, so offering it was an
        // instruction the next step could not carry out.
        if (!succeeded()) return sb.toString();
        sb.append(" · use: ").append(handle());
        for (ArtifactRef ref : shape.refs()) sb.append(", ").append(ref);
        sb.append(" — any of these as the whole value of a tool argument, when you have a tool "
                + "that takes it; the text is substituted here");
        return sb.toString();
    }

    /**
     * The single substitution point. A PUBLIC result enters the trajectory as it is, byte for
     * byte as today; a PRIVATE one enters as its descriptor, with no structured data — the
     * structured map is the same content in another shape.
     */
    public static AgentObservation asObservation(Artifact a, ToolResult r, long durationMs) {
        if (a.isPrivate()) {
            // Metadata, never bytes -- the same shape a delegation reports for its own steps.
            // With Map.of() here a privately-executed direct call was invisible to the withheld
            // line, which then printed nothing at all; and nothing reads as "nothing withheld".
            return new AgentObservation(a.tool(), a.success(), a.describe(),
                    Map.of("artifacts", List.of(Map.of("n", a.n(), "tool", a.tool(),
                            "label", a.label().name(), "chars", a.output().length()))),
                    durationMs);
        }
        return new AgentObservation(a.tool(), a.success(), r.output(),
                r.structured() == null ? Map.of() : r.structured(), durationMs);
    }

    /**
     * Every top-level field of a JSON object result, in key order, as the reference to it --
     * the same references the descriptor offers the cloud. None for anything else.
     */
    static List<ArtifactRef> fieldRefs(int handle, String text) {
        return shapeOf(handle, text).refs();
    }

    /**
     * kind ("json" | "text"); each top-level field as the descriptor lists it, annotated with
     * its kind and size; the value of each boolean field; and the reference to each field -- all
     * in key order, every field, and each field named as {@link ArtifactRef#toField} names it.
     */
    record Shape(String kind, List<String> fields, Map<String, String> primitives,
                 List<ArtifactRef> refs) {
        boolean isJson() { return "json".equals(kind); }
    }

    static Shape shapeOf(int handle, String text) {
        var notJson = new Shape("text", List.of(), Map.of(), List.of());
        if (text == null || text.isBlank()) return notJson;
        String t = text.strip();
        if (!(t.startsWith("{") && t.endsWith("}"))) return notJson;
        try {
            JsonNode node = MAPPER.readTree(t);
            if (node == null || !node.isObject()) return notJson;
            var fields = new ArrayList<String>();
            var primitives = new java.util.LinkedHashMap<String, String>();
            var refs = new ArrayList<ArtifactRef>();
            var it = node.fields();
            while (it.hasNext()) {
                var e = it.next();
                ArtifactRef ref = ArtifactRef.toField(handle, e.getKey(), refs.size() + 1);
                refs.add(ref);
                String name = ref.field();
                JsonNode v = e.getValue();
                if (v.isBoolean()) {
                    // Booleans only. ok=false must be visible -- a success envelope around a
                    // failure is the normal shape of a skill result and hiding it would have the
                    // cloud report a send that never happened. A NUMBER can be the secret itself
                    // (a balance, a count of messages), so it gets its kind and nothing more.
                    primitives.put(name, v.asText());
                    fields.add(name);
                } else if (v.isNumber()) {
                    fields.add(name + " (number)");
                } else if (v.isTextual()) {
                    fields.add(name + " (string, " + String.format("%,d", v.asText().length()) + " chars)");
                } else if (v.isArray()) {
                    fields.add(name + " (array, " + v.size() + ")");
                } else if (v.isObject()) {
                    fields.add(name + " (object, " + v.size() + " fields)");
                } else {
                    fields.add(name);
                }
            }
            return new Shape("json", List.copyOf(fields),
                    java.util.Collections.unmodifiableMap(primitives), List.copyOf(refs));
        } catch (Exception e) {
            return notJson;
        }
    }
}
