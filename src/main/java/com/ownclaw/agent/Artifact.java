package com.ownclaw.agent;

import com.fasterxml.jackson.databind.JsonNode;
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
 * The label comes from facts the code already has: in {@link #labelFor}, the skill reads a
 * personal-content source -- its credentials name one, mail by default -- or the call pulled in a
 * PRIVATE result; the task holds a file; inside a delegation, a step that came after one of those
 * (see {@code LocalExecutor}); and a result whose bytes repeat a PRIVATE one
 * ({@code AgentContext.decide}). Not from a model, and not from a rule list of tool names. Every
 * other result goes to the cloud as it is, through the gateway's filter, which removes secrets
 * and replaces identifiers wherever they are.
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
     * reads a personal-content source, or the call pulled in a PRIVATE result. (Inside a
     * delegation there is a third, about timing: after the local model has read private data,
     * everything it does is PRIVATE. That one is applied by {@code AgentContext.decide}, which both
     * paths call.) Needing credentials is not one of them: a router's configuration read with a
     * password goes to the cloud with the password removed.
     *
     * @param personalSource whether the skill's credentials name a personal-content source
     *                       ({@code AgentContext.readsPersonalSource}): its output is that content
     * @param used           the results this call's arguments actually pulled in, as the
     *                       resolver substituted them — so the label describes what moved
     *                       rather than re-reading the arguments and guessing
     */
    public static Decision labelFor(boolean personalSource, List<Artifact> used) {
        var why = new ArrayList<String>();
        if (personalSource) {
            // That it is one, not the key names. A vault miss is worded "Missing required
            // credentials: IMAP_PASS, IMAP_USER" by the skill harness, so naming them here put a
            // 40-character run of the output into the descriptor -- and the descriptor is what
            // the cloud reads, so the canary refused the call that carried it and the run died
            // instead of saying "credentials missing". The key names are already in the tool
            // schema the cloud holds, and the raw text is in the skill_usage row the owner reads.
            why.add("personal source");
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
        JsonNode node = ToolResult.jsonObject(output);
        if (node == null) return true;
        // The whole top level: when the descriptor listed only the first twelve keys, an "ok"
        // in thirteenth place was read as success. A string "false" counts too; some skills
        // write one.
        for (String key : List.of("ok", "success")) {
            JsonNode v = node.get(key);
            if (v != null && (v.isBoolean() ? !v.asBoolean() : "false".equalsIgnoreCase(v.asText()))) {
                return false;
            }
        }
        return true;
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
     * shown only when it is shorter than a canary window ({@link ArtifactRef#toField}) and the
     * descriptor carrying it carries no window of this result ({@link #withheld}); any other is
     * named by its position.
     */
    public String describe() {
        return render(shapeOf(n, output, withheld()));
    }

    /** The descriptor, its fields named as {@code shape} names them. */
    private String render(Shape shape) {
        var sb = new StringBuilder(handle()).append(' ').append(tool)
                .append(succeeded() ? " ✓" : " ✗").append(" — ").append(label);
        if (!why.isEmpty()) sb.append(" (").append(String.join("; ", why)).append(')');
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
        sb.append(" — any of these in a tool argument, as the whole value or inside text, when "
                + "you have a tool that takes it; the text is substituted here");
        return sb.toString();
    }

    /**
     * The single substitution point. A PUBLIC result enters the trajectory as it is, byte for
     * byte as today; a PRIVATE one enters as its descriptor, with metadata about the artifact in
     * place of its bytes.
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
        return new AgentObservation(a.tool(), a.success(), r.output(), Map.of(), durationMs);
    }

    /**
     * Every top-level field of a JSON object result, in key order, as the reference to it --
     * the references the descriptor offers the cloud. None for anything else.
     */
    List<ArtifactRef> fieldRefs() {
        return shapeOf(n, output, withheld()).refs();
    }

    /**
     * The positions of the fields whose names the descriptor withholds though they are shorter
     * than a window: the ones that would make it carry a window of this result.
     * <p>
     * A key can be data -- the address a mail summary is keyed by -- and the descriptor prints
     * it between separators: after "fields: " or ", ", before " (number)" or "=true", between
     * "{{1." and "}}". With the space before it a name of 31 characters is a whole window, with
     * ", " before it and " (" after it one of 28; so when the result also mentions the key in its
     * text -- "5 new messages from" the address -- the descriptor holds a window of the result,
     * and the gateway refuses every request that carries it, on every run of the skill. No
     * length rules that out: it depends on what surrounds the name. So the canary is asked, of
     * the descriptor as it is written and as a think prompt sends it, on lines of its own
     * ({@code PrivateIndex.firstLeakInResult}), against this result's own windows. In each run it
     * finds, the longest name the run holds is offered by its position instead, and the
     * descriptor is asked again until it holds no run; a run that holds no whole name withholds
     * every name.
     * <p>
     * Only for a PRIVATE result that is indexed: any other shows no name ({@link #render}), or
     * holds nothing the canary looks for. A later private result that mentions a key the same way
     * is a collision the door refuses, like any other.
     */
    private java.util.Set<Integer> withheld() {
        var byPosition = new java.util.HashSet<Integer>();
        if (!isPrivate() || !indexed) return byPosition;
        com.ownclaw.privacy.PrivateIndex own = null;
        while (true) {
            Shape shape = shapeOf(n, output, byPosition);
            var shown = new java.util.LinkedHashMap<Integer, String>();   // position -> name, normalised
            for (int k = 1; k <= shape.refs().size(); k++) {
                ArtifactRef ref = shape.refs().get(k - 1);
                if (ref.position() == null) shown.put(k, com.ownclaw.privacy.PrivateIndex.normalise(ref.field()));
            }
            if (shown.isEmpty()) return byPosition;
            if (own == null) {
                own = new com.ownclaw.privacy.PrivateIndex();
                own.addPrivate(n, output);
            }
            var runs = new ArrayList<String>();
            // Every run it is asked about excused, the scan reads on to the end and asks about each.
            own.firstLeakInResult(render(shape), (handle, run) -> runs.add(run));
            if (runs.isEmpty()) return byPosition;
            var withhold = new java.util.HashSet<Integer>();
            for (String run : runs) {
                int longest = 0;
                var longestNames = new ArrayList<Integer>();
                for (var name : shown.entrySet()) {
                    int length = name.getValue().length();
                    if (length < longest || !run.contains(name.getValue())) continue;
                    if (length > longest) longestNames.clear();
                    longest = length;
                    longestNames.add(name.getKey());
                }
                withhold.addAll(longestNames);
            }
            byPosition.addAll(withhold.isEmpty() ? shown.keySet() : withhold);
        }
    }

    /**
     * kind ("json" | "text"); each top-level field as the descriptor lists it, annotated with
     * its kind and size; the value of each boolean field; and the reference to each field -- all
     * in key order, every field.
     */
    record Shape(String kind, List<String> fields, Map<String, String> primitives,
                 List<ArtifactRef> refs) {
        boolean isJson() { return "json".equals(kind); }
    }

    /**
     * The shape of {@code text}, each field named as {@link ArtifactRef#toField} names it -- but
     * for the fields at the positions in {@code byPosition}, named by their position
     * ({@link #withheld}).
     */
    static Shape shapeOf(int handle, String text, java.util.Set<Integer> byPosition) {
        JsonNode node = ToolResult.jsonObject(text);
        if (node == null) return new Shape("text", List.of(), Map.of(), List.of());
        var fields = new ArrayList<String>();
        var primitives = new java.util.LinkedHashMap<String, String>();
        var refs = new ArrayList<ArtifactRef>();
        var it = node.fields();
        while (it.hasNext()) {
            var e = it.next();
            int position = refs.size() + 1;
            ArtifactRef ref = byPosition.contains(position) ? ArtifactRef.atPosition(handle, position)
                    : ArtifactRef.toField(handle, e.getKey(), position);
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
    }
}
