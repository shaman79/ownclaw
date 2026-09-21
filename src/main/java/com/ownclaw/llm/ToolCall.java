package com.ownclaw.llm;

import java.util.Map;

/**
 * A tool invocation the model asked for, normalised across providers.
 *
 * <p>Anthropic returns a {@code tool_use} content block whose {@code input} is already an object;
 * OpenAI returns {@code tool_calls[].function.arguments} as a JSON <em>string</em> that has to be
 * parsed; Ollama returns an object again. Providers do that translation so the agent sees one
 * shape.
 *
 * @param id the provider's call id. Not used while history is replayed as prose, but recorded
 *           because a structured transcript would need it and it is worth having in a log.
 */
public record ToolCall(String id, String name, Map<String, Object> arguments) {}
