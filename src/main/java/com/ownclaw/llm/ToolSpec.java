package com.ownclaw.llm;

import java.util.Map;

/**
 * One tool, described so a provider can offer it natively.
 *
 * <p>Provider-neutral on purpose: {@code inputSchema} is a plain JSON-Schema-shaped Map rather
 * than a Jackson node, so nothing in the agent packages has to know which serialiser a provider
 * uses, and the schema can be asserted in a unit test without a provider present.
 *
 * @param inputSchema a JSON Schema object, normally {@code {"type":"object","properties":{…}}}
 */
public record ToolSpec(String name, String description, Map<String, Object> inputSchema) {}
