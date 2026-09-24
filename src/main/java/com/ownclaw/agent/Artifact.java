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
 * delegation — becomes one of these, numbered {@code $1, $2, ...} for the whole task. The bytes
 * stay here, in memory, for the task's lifetime. What enters the trajectory, the chat rows and
 * every cloud prompt is decided ONCE, at record time, by {@link #asObservation}: a PUBLIC result
 * goes in as it is, exactly as today; a PRIVATE one goes in as its {@link #describe descriptor}
 * — the handle, the tool, the label and why, the size and shape, and the value of any top-level
 * boolean so that {@code ok=false} is never hidden inside an envelope. Nothing
 * downstream needs to know about labels, because nothing downstream ever holds the bytes.
 * <p>
 * The label comes from facts the code already has, in {@link #labelFor}: the skill declared
 * credentials; the task carries an attachment; a parameter references something already
 * PRIVATE; or an earlier step of the same delegation was. Not from a model, and not from a rule
 * list — the moment the label needs a taxonomy of tool names, this design has failed.
 *
 * @param n        the task-wide handle number; {@code $n} in every prompt and ledger
 * @param tool     the tool or skill that produced it
 * @param written  the arguments exactly as the model typed them — references intact
 * @param resolved the arguments after reference substitution, which is what actually ran;
 *                 the guards compare these, and only {@code written} is ever printed
 * @param output   the bytes
 * @param success  whether the tool reported success
 * @param label    whether the bytes may leave this machine
 * @param why      which facts made it PRIVATE, for the descriptor and the ledger
 */
public record Artifact(int n, String tool, Map<String, Object> written,
                       Map<String, Object> resolved, String output, boolean success,
                       Label label, List<String> why) {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** How many top-level field names a descriptor shows, and how long each may be. */
    static final int MAX_FIELDS = 12;
    static final int MAX_FIELD_NAME = 24;

    /** How many names the reference list may carry. Names are short; the descriptor's cap is not. */
    static final int MAX_REFERENCE_NAMES = 48;

    /**
     * Characters the descriptor may spend naming fields past {@link #MAX_FIELDS}.
     * <p>
     * Bounded by length rather than by count, because the thing to avoid is a descriptor that
     * has become the content, and twelve was too few to be a limit on NAMES: an imap envelope
     * puts {@code body_text} past the twelfth key, and a field the cloud is never shown is a
     * field it cannot reference — so it composed the email from the description instead.
     */
    static final int MAX_OVERFLOW_NAME_CHARS = 240;

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

    /** A PUBLIC artifact with no handle yet — the shape a test or a legacy caller builds. */
    public Artifact(String tool, Map<String, Object> params, String output, boolean success) {
        this(0, tool, params, params, output, success, Label.PUBLIC, List.of());
    }

    /** {@code $3} */
    public String handle() {
        return "$" + n;
    }

    public boolean isPrivate() {
        return label == Label.PRIVATE;
    }

    /**
     * The label, from the facts at hand.
     *
     * @param requiredCredentials what the skill declared; non-empty means it reached something
     *                            that needed a secret, and its output is that something
     * @param contextTainted      an earlier step of this delegation was PRIVATE, so the local
     *                            model has read private content and anything it writes now
     *                            may carry it — into a public tool's arguments included
     * @param written             the arguments as typed; a {@code $N} reference to a PRIVATE
     *                            artifact makes the result PRIVATE, because it is derived from it
     * @param store               the task's artifacts so far, for resolving those references
     */
    public static Decision labelFor(List<String> requiredCredentials,
                                    boolean contextTainted, Map<String, Object> written,
                                    List<Artifact> store) {
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
        // There was an "attachment" reason here. It could not fire: a file reaches a task only
        // through attended chat (priority 1), and on an attended task the attachment artifact is
        // deliberately PUBLIC so that "summarise this" still works. Gating it on unattended made
        // it unreachable everywhere rather than at two sites; removing the gate would have taken
        // that capability away instead. What covers the case is already here -- an unattended
        // attachment artifact is PRIVATE where it is recorded, and anything referencing it is
        // PRIVATE by the clause below. A reason that cannot fire is not a safeguard.
        if (contextTainted) why.add("after a private step");
        if (written != null && store != null) {
            for (Object v : written.values()) {
                if (!(v instanceof String s)) continue;
                // The one grammar, shared with the resolver and the refusal. Three private
                // copies of this question used to give three answers, and every gap between
                // them moved private bytes under a PUBLIC label.
                ArtifactRef ref = ArtifactRef.parse(s);
                if (ref == null) continue;
                for (Artifact a : store) {
                    if (a.n() == ref.handle() && a.isPrivate()) {
                        why.add("references " + a.handle());
                        break;
                    }
                }
            }
        }
        return new Decision(why.isEmpty() ? Label.PUBLIC : Label.PRIVATE, List.copyOf(why));
    }

    /** A label and the facts that produced it. */
    public record Decision(Label label, List<String> why) {}

    /**
     * What may be said about a PRIVATE artifact: everything except its content.
     * <p>
     * Top-level booleans are shown WITH their values. A success envelope around a failure —
     * {@code {"ok": false, "error": "..."}} — is the normal shape of a skill result, and a
     * descriptor that hid {@code ok=false} would have the cloud report a send that never
     * happened. Everything else is shown as a kind and a size: a string is where the data is,
     * and a number can BE the data — a balance, a count of unread messages.
     */
    public String describe() {
        var sb = new StringBuilder(handle()).append(' ').append(tool)
                .append(success ? " ✓" : " ✗").append(" — ").append(label);
        if (!why.isEmpty()) sb.append(" (").append(String.join("; ", why)).append(')');
        Shape shape = shapeOf(output);
        sb.append(" · ").append(shape.kind()).append(" · ")
          .append(String.format("%,d", output.length())).append(" chars");
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
        // with no menu in it. A template ("pass $1.<field>") was worse, beside a field list that
        // annotates its names: the cloud composed "$1.body_text (string, 48 chars)", which
        // resolves to nothing, and the literal went out as the body. So: the exact strings, in
        // one list, for every field -- including those past the annotated twelve, since an imap
        // envelope puts body_text well beyond them. Cut short like the annotated names, because a
        // full-length name would be a window of the private text; the resolver takes a cut name
        // by its prefix. The substitution happens here, so naming the handle discloses nothing.
        sb.append(" · use: ").append(handle());
        int budget = MAX_OVERFLOW_NAME_CHARS, shown = 0;
        for (String name : referenceOrder(output)) {
            String cut = name.length() > MAX_FIELD_NAME
                    ? name.substring(0, MAX_FIELD_NAME) + "…" : name;
            String token = ", " + handle() + "." + cut;
            if (budget - token.length() < 0) break;
            budget -= token.length();
            sb.append(token);
            shown++;
        }
        // Counted from the JSON rather than from the name list, which is itself capped: taking
        // the remainder from that list told the cloud 22 fields were unnamed when 34 were.
        int unnamed = topLevelKeyCount(output) - shown;
        if (unnamed > 0) sb.append(" +").append(unnamed).append(" more");
        sb.append(" — any of these as a whole argument value; the text is substituted here");
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
     * Field names in the order worth offering as references: text fields largest first, then
     * everything else in key order.
     * <p>
     * The list is budgeted, so its order decides which fields get named at all. Key order spent
     * the budget on the envelope -- from, to, subject, date, uid, flags -- and an imap result's
     * body_text, thirteenth or later, was never offered, so the cloud had nothing to forward and
     * wrote the email itself. The content a task forwards is text, and it is the biggest text in
     * the result. A boolean or a count is not something anyone passes along as a body.
     */
    static List<String> referenceOrder(String text) {
        if (text == null || text.isBlank()) return List.of();
        String t = text.strip();
        if (!(t.startsWith("{") && t.endsWith("}"))) return List.of();
        try {
            JsonNode node = MAPPER.readTree(t);
            if (node == null || !node.isObject()) return List.of();
            var textual = new ArrayList<Map.Entry<String, Integer>>();
            var rest = new ArrayList<String>();
            var it = node.fields();
            while (it.hasNext() && textual.size() + rest.size() < MAX_REFERENCE_NAMES) {
                var e = it.next();
                if (e.getValue().isTextual()) {
                    textual.add(Map.entry(e.getKey(), e.getValue().asText().length()));
                } else {
                    rest.add(e.getKey());
                }
            }
            // Stable, so equal lengths keep key order.
            textual.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            var out = new ArrayList<String>();
            for (var e : textual) out.add(e.getKey());
            out.addAll(rest);
            return List.copyOf(out);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** How many top-level keys a JSON object result has; 0 for anything that is not one. */
    static int topLevelKeyCount(String text) {
        if (text == null || text.isBlank()) return 0;
        String t = text.strip();
        if (!(t.startsWith("{") && t.endsWith("}"))) return 0;
        try {
            JsonNode node = MAPPER.readTree(t);
            return node != null && node.isObject() ? node.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * The top-level field names EXACTLY as the JSON spells them, for telling a model what it
     * may reference.
     * <p>
     * Parsed here rather than unwrapped from the descriptor's list. That list truncates a name
     * longer than {@link #MAX_FIELD_NAME} and marks the cut with an ellipsis, and a model told
     * to reference a name exactly copies the ellipsis with it: {@code $1.rendered_html_for_ema…}
     * resolves to nothing, and the unresolved-reference guard does not recognise it as a
     * reference either, so the literal travels on as the argument. Untruncated here, bounded
     * there; the two lists answer different questions.
     */
    public static List<String> jsonFieldNames(String text) {
        if (text == null || text.isBlank()) return List.of();
        String t = text.strip();
        if (!(t.startsWith("{") && t.endsWith("}"))) return List.of();
        try {
            JsonNode node = MAPPER.readTree(t);
            if (node == null || !node.isObject()) return List.of();
            var names = new ArrayList<String>();
            // Bounded, but not at MAX_FIELDS. That cap is the descriptor's, and applying it here
            // hid the thirteenth key from the local model too -- on an imap envelope body_text
            // sits past the twelfth, so the one field the task needed could be named by nobody.
            var it = node.fieldNames();
            while (it.hasNext() && names.size() < MAX_REFERENCE_NAMES) names.add(it.next());
            return List.copyOf(names);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** kind ("json" | "text" | "error"), field names, and primitive values, of a result. */
    record Shape(String kind, List<String> fields, Map<String, String> primitives) {
        boolean isJson() { return "json".equals(kind); }
    }

    static Shape shapeOf(String text) {
        if (text == null || text.isBlank()) return new Shape("text", List.of(), Map.of());
        String t = text.strip();
        if (!(t.startsWith("{") && t.endsWith("}"))) return new Shape("text", List.of(), Map.of());
        try {
            JsonNode node = MAPPER.readTree(t);
            if (node == null || !node.isObject()) return new Shape("text", List.of(), Map.of());
            var fields = new ArrayList<String>();
            var primitives = new java.util.LinkedHashMap<String, String>();
            var it = node.fields();
            while (it.hasNext() && fields.size() < MAX_FIELDS) {
                var e = it.next();
                String name = e.getKey().length() > MAX_FIELD_NAME
                        ? e.getKey().substring(0, MAX_FIELD_NAME) + "…" : e.getKey();
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
            return new Shape("json", List.copyOf(fields), Map.copyOf(primitives));
        } catch (Exception e) {
            return new Shape("text", List.of(), Map.of());
        }
    }
}
