package com.ownclaw.llm;

import java.util.List;
import java.util.Map;

/**
 * One tool, described so a provider can offer it natively.
 *
 * <p>Provider-neutral on purpose: {@code inputSchema} is a plain JSON-Schema-shaped Map rather
 * than a Jackson node, so nothing in the agent packages has to know which serialiser a provider
 * uses, and the schema can be asserted in a unit test without a provider present.
 *
 * @param inputSchema a JSON Schema object, normally {@code {"type":"object","properties":{…}}}
 * @param deferred    offered only from a {@link LlmMessage.Role#TOOLS} message naming it: sent with
 *                    the request, but not part of what the model reads until then
 * @param authored    its description and schema are this application's code, holding nothing a
 *                    user, a tool or a model wrote -- the special actions: the cloud gateway removes
 *                    the vault's values from them and replaces no identifier
 *                    ({@link LlmMessage#authored}). A skill's text is written by a model and can
 *                    name the owner's hosts; it is filtered whole.
 */
public record ToolSpec(String name, String description, Map<String, Object> inputSchema, boolean deferred,
                       boolean authored) {

    public ToolSpec(String name, String description, Map<String, Object> inputSchema) {
        this(name, description, inputSchema, false, false);
    }

    public ToolSpec(String name, String description, Map<String, Object> inputSchema, boolean deferred) {
        this(name, description, inputSchema, deferred, false);
    }

    public ToolSpec asDeferred() {
        return new ToolSpec(name, description, inputSchema, true, authored);
    }

    /** This spec as the code's own text: see {@link #authored}. */
    public ToolSpec asAuthored() {
        return new ToolSpec(name, description, inputSchema, deferred, true);
    }

    /**
     * What a provider without deferred loading offers: the tools that are not deferred, in their
     * order, then the deferred ones in the order the conversation's {@link LlmMessage.Role#TOOLS}
     * messages name them -- appended, so the part of the prompt a model has read stays the same.
     */
    public static List<ToolSpec> offered(List<ToolSpec> tools, List<LlmMessage> messages) {
        if (tools == null) return List.of();
        var out = new java.util.ArrayList<ToolSpec>();
        var deferred = new java.util.LinkedHashMap<String, ToolSpec>();
        for (ToolSpec t : tools) {
            if (t.deferred()) deferred.put(t.name(), t);
            else out.add(t);
        }
        for (LlmMessage m : messages) {
            for (String name : m.addedTools()) {
                ToolSpec t = deferred.remove(name);
                if (t != null) out.add(new ToolSpec(t.name(), t.description(), t.inputSchema(), false,
                        t.authored()));   // offered now
            }
        }
        return out;
    }
}
