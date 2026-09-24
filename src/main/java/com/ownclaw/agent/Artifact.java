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
     * The label, from the facts at hand — two of them, and nothing a model decides.
     * <p>
     * There used to be a third: once a delegation had touched anything private, every later
     * result of it was PRIVATE too. That marked the restaurant page a delegation fetched after
     * sending an email as private, and when the cloud later fetched the same public page itself
     * the canary refused the call — so the run reported "did not finish" after the email had
     * already gone. The worry behind it was real, but it is about what the local model WRITES
     * after reading private content, not about what a public tool returns; the local model's
     * own words are withheld from the cloud by {@code LocalExecutor.completed} instead.
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
        // An attachment is covered here too: an unattended one is recorded PRIVATE, and a call
        // that pulls it in is PRIVATE by this clause. (A separate attachment rule could never
        // fire -- files only arrive on attended chat, where they are deliberately PUBLIC.)
        if (used != null) {
            for (Artifact a : used) {
                if (a.isPrivate()) why.add("references " + a.handle());
            }
        }
        return new Decision(why.isEmpty() ? Label.PUBLIC : Label.PRIVATE, List.copyOf(why));
    }

    /** A label and the facts that produced it. */
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
        var p = shapeOf(output).primitives();
        return !"false".equals(p.get("ok")) && !"false".equals(p.get("success"));
    }

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
                .append(succeeded() ? " ✓" : " ✗").append(" — ").append(label);
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
        // with no menu in it. A template ("pass <handle>.<field>") was worse, beside a field list
        // that annotates its names: the cloud composed "body_text (string, 48 chars)" into the
        // reference, which resolved to nothing, and the literal went out as the body. So: the exact strings, in
        // one list, for every field -- including those past the annotated twelve, since an imap
        // envelope puts body_text well beyond them. Cut short like the annotated names, because a
        // full-length name would be a window of the private text; the resolver takes a cut name
        // by its prefix. The substitution happens here, so naming the handle discloses nothing.
        // Not for a result that failed: the resolver refuses it, so offering it was an
        // instruction the next step could not carry out.
        if (!succeeded()) return sb.toString();
        sb.append(" · use: ").append(handle());
        int budget = MAX_OVERFLOW_NAME_CHARS, shown = 0;
        for (String name : referenceOrder(output)) {
            String cut = name.length() > MAX_FIELD_NAME
                    ? name.substring(0, MAX_FIELD_NAME) + "…" : name;
            String token = ", " + new ArtifactRef(n, cut);
            if (budget - token.length() < 0) break;
            budget -= token.length();
            sb.append(token);
            shown++;
        }
        // Counted from the JSON rather than from the name list, which is itself capped: taking
        // the remainder from that list told the cloud 22 fields were unnamed when 34 were.
        int unnamed = topLevelKeyCount(output) - shown;
        if (unnamed > 0) sb.append(" +").append(unnamed).append(" more");
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
     * to reference a name exactly copies the ellipsis with it: {@code {{1.rendered_html_for_ema…}}}
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
