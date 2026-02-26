package com.ownclaw.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry that discovers and manages all available tools at runtime.
 * Tools are registered as Spring beans implementing the {@link Tool} interface.
 *
 * The registry provides:
 * - Lookup by name
 * - Listing all available tools
 * - Generating a tool manifest for injection into LLM prompts
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    public ToolRegistry(List<Tool> discoveredTools) {
        for (Tool tool : discoveredTools) {
            if (tools.containsKey(tool.name())) {
                log.warn("Duplicate tool name '{}' — overwriting with {}", tool.name(), tool.getClass().getSimpleName());
            }
            tools.put(tool.name(), tool);
            log.info("Registered tool: {} ({})", tool.name(), tool.getClass().getSimpleName());
        }
        log.info("Tool registry initialized with {} tools", tools.size());
    }

    /**
     * Find a tool by its exact name.
     */
    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * Get all registered tools.
     */
    public Collection<Tool> all() {
        return Collections.unmodifiableCollection(tools.values());
    }

    /**
     * Get tool names as a set.
     */
    public Set<String> names() {
        return Collections.unmodifiableSet(tools.keySet());
    }

    /**
     * Register a tool dynamically at runtime (e.g., generated skills or loaded plugins).
     */
    public void register(Tool tool) {
        tools.put(tool.name(), tool);
        log.info("Dynamically registered tool: {}", tool.name());
    }

    /**
     * Remove a tool by name.
     */
    public void unregister(String name) {
        tools.remove(name);
        log.info("Unregistered tool: {}", name);
    }

    /**
     * Generate a compact tool manifest string suitable for injection into an LLM system prompt.
     * Format:
     *   tool_name: description
     *     param_name (type, required|optional): description
     */
    public String generateManifest() {
        return generateManifest(tools.values());
    }

    /**
     * Generate a manifest for a specific subset of tools.
     */
    public String generateManifest(Collection<Tool> subset) {
        return subset.stream()
                .sorted(Comparator.comparing(Tool::name))
                .map(this::formatToolEntry)
                .collect(Collectors.joining("\n\n"));
    }

    private String formatToolEntry(Tool tool) {
        var sb = new StringBuilder();
        sb.append(tool.name()).append(": ").append(tool.description());

        if (tool.requiresNetwork()) sb.append(" [network]");
        if (tool.hasSideEffects()) sb.append(" [side-effects]");

        Map<String, ToolParam> schema = tool.inputSchema();
        if (schema != null && !schema.isEmpty()) {
            for (var entry : schema.entrySet()) {
                sb.append("\n  ").append(entry.getKey());
                ToolParam param = entry.getValue();
                sb.append(" (").append(param.type());
                sb.append(", ").append(param.required() ? "required" : "optional");
                sb.append("): ").append(param.description());
            }
        }

        return sb.toString();
    }
}
