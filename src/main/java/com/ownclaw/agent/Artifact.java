package com.ownclaw.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.privacy.Label;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One tool result, as the task keeps it: the bytes, and what may be said about them.
 * <p>
 * Every result a task produces — by the cloud directly, or by the local model inside a
 * delegation — becomes one of these, numbered {@code $1, $2, ...} for the whole task. The bytes
 * stay here, in memory, for the task's lifetime. What enters the trajectory, the chat rows and
 * every cloud prompt is decided ONCE, at record time, by {@link #asObservation}: a PUBLIC result
 * goes in as it is, exactly as today; a PRIVATE one goes in as its {@link #describe descriptor}
 * — the handle, the tool, the label and why, the size and shape, and the values of any top-level
 * booleans and numbers so that {@code ok=false} is never hidden inside an envelope. Nothing
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
    private static final Pattern REF = Pattern.compile("^\\$(\\d+)(?:\\.[A-Za-z0-9_]+)?$");
    /** How many top-level field names a descriptor shows, and how long each may be. */
    static final int MAX_FIELDS = 12;
    static final int MAX_FIELD_NAME = 40;

    public Artifact {
        written = written == null ? Map.of() : Map.copyOf(written);
        resolved = resolved == null ? Map.of() : Map.copyOf(resolved);
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
     * @param taskHasAttachments  the task was given files; everything produced on it is theirs
     * @param contextTainted      an earlier step of this delegation was PRIVATE, so the local
     *                            model has read private content and anything it writes now
     *                            may carry it — into a public tool's arguments included
     * @param written             the arguments as typed; a {@code $N} reference to a PRIVATE
     *                            artifact makes the result PRIVATE, because it is derived from it
     * @param store               the task's artifacts so far, for resolving those references
     */
    public static Decision labelFor(List<String> requiredCredentials, boolean taskHasAttachments,
                                    boolean contextTainted, Map<String, Object> written,
                                    List<Artifact> store) {
        var why = new ArrayList<String>();
        if (requiredCredentials != null && !requiredCredentials.isEmpty()) {
            why.add("credentials: " + String.join(", ", requiredCredentials));
        }
        if (taskHasAttachments) why.add("attachment");
        if (contextTainted) why.add("after a private step");
        if (written != null && store != null) {
            for (Object v : written.values()) {
                if (!(v instanceof String s)) continue;
                Matcher m = REF.matcher(s.strip());
                if (!m.matches()) continue;
                int ref = Integer.parseInt(m.group(1));
                for (Artifact a : store) {
                    if (a.n() == ref && a.isPrivate()) {
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
     * Top-level booleans and numbers are shown WITH their values. A success envelope around a
     * failure — {@code {"ok": false, "error": "..."}} — is the normal shape of a skill result,
     * and a descriptor that hid {@code ok=false} would have the cloud report a send that never
     * happened. Strings are shown as a kind and a size; a string is where the data is.
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
        return sb.toString();
    }

    /**
     * The single substitution point. A PUBLIC result enters the trajectory as it is, byte for
     * byte as today; a PRIVATE one enters as its descriptor, with no structured data — the
     * structured map is the same content in another shape.
     */
    public static AgentObservation asObservation(Artifact a, ToolResult r, long durationMs) {
        if (a.isPrivate()) {
            return new AgentObservation(a.tool(), a.success(), a.describe(), Map.of(), durationMs);
        }
        return new AgentObservation(a.tool(), a.success(), r.output(),
                r.structured() == null ? Map.of() : r.structured(), durationMs);
    }

    /** The top-level field names of a JSON object, or none. One parser for every caller. */
    public static List<String> jsonFields(String text) {
        return shapeOf(text).fields();
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
                if (v.isBoolean() || v.isNumber()) {
                    primitives.put(name, v.asText());
                    fields.add(name);
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
