package com.ownclaw.agent.tools;

import com.ownclaw.llm.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns the live tool registry into JSON Schema a provider will accept.
 *
 * <p>The schema data already exists — {@link Tool#inputSchema()} returns
 * {@code Map<String, ToolParam>} — it has only ever been rendered as prose for the prompt. This
 * class renders the same information as structure instead. Everything here is static and free of
 * Spring so it can be tested directly, which matters because the input is not trustworthy: every
 * tool is a Python skill the model wrote at runtime, and {@code SKILL.yaml} type strings are
 * whatever it felt like writing that day.
 */
public final class ToolSchemas {

    private static final Logger log = LoggerFactory.getLogger(ToolSchemas.class);

    /** What the providers accept as a tool name. One bad name rejects the entire request. */
    private static final Pattern API_SAFE_NAME = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private ToolSchemas() { /* static only */ }

    /** Whether a provider will accept this as a tool name. */
    public static boolean isApiSafeName(String name) {
        return name != null && API_SAFE_NAME.matcher(name).matches();
    }

    /**
     * Map a declared type onto a JSON Schema type.
     *
     * <p>An unrecognised type omits the {@code type} keyword entirely, which is valid JSON Schema
     * and means "anything". That is deliberate: guessing {@code string} for a parameter that is
     * really a list makes the provider reject the model's correct call, and a wrong type is worse
     * than no type. These strings come from LLM-authored YAML, so the unrecognised case is
     * ordinary rather than exceptional.
     *
     * @return the JSON Schema type, or null to omit the keyword
     */
    public static String normalizeType(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String t = raw.trim().toLowerCase(Locale.ROOT);
        // "List[str]", "list of strings", "array<string>" all start with a container word.
        if (t.startsWith("list") || t.startsWith("array") || t.startsWith("sequence")) return "array";
        if (t.startsWith("dict") || t.startsWith("object") || t.startsWith("map")) return "object";
        return switch (t) {
            case "str", "string", "text" -> "string";
            case "int", "integer" -> "integer";
            case "float", "double", "number", "decimal" -> "number";
            case "bool", "boolean" -> "boolean";
            default -> null;
        };
    }

    /** A JSON Schema object for a tool's parameters. */
    public static Map<String, Object> toJsonSchema(Map<String, ToolParam> params) {
        var properties = new LinkedHashMap<String, Object>();
        var required = new ArrayList<String>();
        if (params != null) {
            for (var e : params.entrySet()) {
                ToolParam p = e.getValue();
                if (p == null) continue;
                var prop = new LinkedHashMap<String, Object>();
                String type = normalizeType(p.type());
                if (type != null) prop.put("type", type);
                if (p.description() != null && !p.description().isBlank()) {
                    prop.put("description", p.description());
                }
                properties.put(e.getKey(), prop);
                if (p.required()) required.add(e.getKey());
            }
        }
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        // Omitted rather than sent empty: some providers treat [] as "explicitly nothing is
        // required" and others as malformed, and there is no reason to find out which.
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }

    /**
     * The description a provider sees, carrying the facts JSON Schema has no field for.
     *
     * <p>Whether a tool reaches the network, whether it has side effects, and whether the
     * credentials it needs are actually present are all real information the prose manifest
     * renders today. Dropping them when moving to schemas would be a quiet downgrade.
     */
    public static String describe(Tool tool, List<String> availableCredentials) {
        var sb = new StringBuilder(tool.description() == null ? "" : tool.description());
        var flags = new ArrayList<String>();
        if (tool.requiresNetwork()) flags.add("needs network");
        if (tool.hasSideEffects()) flags.add("has side effects");
        for (String key : tool.requiredCredentials()) {
            boolean present = availableCredentials != null && availableCredentials.contains(key);
            flags.add("credential " + key + (present ? " available" : " NOT SET"));
        }
        if (!flags.isEmpty()) sb.append(" (").append(String.join("; ", flags)).append(")");
        return sb.toString();
    }

    /**
     * Build the tool list for a request: the special actions first, then the registry.
     *
     * <p>The special actions are not {@link Tool} beans and never will be — the owner removed
     * built-in Java tools by decree — so they are described separately and merged here purely for
     * the provider's benefit. A registry skill whose name collides with one of them loses: the
     * loop branches on the sentinel name, so a skill called {@code respond} could never have been
     * reachable anyway, and sending it twice would reject the whole request.
     *
     * <p>A tool whose name a provider would refuse is skipped rather than allowed to fail the
     * request, because one bad name breaks every step of every task, not just that tool.
     */
    public static List<ToolSpec> build(List<ToolSpec> specialActions,
                                       Collection<Tool> tools,
                                       List<String> availableCredentials) {
        var out = new ArrayList<ToolSpec>();
        Set<String> taken = new LinkedHashSet<>();
        if (specialActions != null) {
            for (ToolSpec spec : specialActions) {
                if (taken.add(spec.name())) out.add(spec);
            }
        }
        if (tools != null) {
            tools.stream()
                    .filter(t -> t != null && t.name() != null)
                    .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                    .forEach(t -> {
                        if (!isApiSafeName(t.name())) {
                            log.warn("Skipping tool '{}': the name is not acceptable to the "
                                    + "provider, and one bad name rejects the whole request.",
                                    t.name());
                            return;
                        }
                        if (!taken.add(t.name())) {
                            log.debug("Skipping '{}': a special action already owns that name.",
                                    t.name());
                            return;
                        }
                        out.add(new ToolSpec(t.name(), describe(t, availableCredentials),
                                toJsonSchema(t.inputSchema())));
                    });
        }
        return out;
    }
}
