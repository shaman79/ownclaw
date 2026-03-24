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
        return generateManifest(subset, List.of());
    }

    /**
     * Generate a manifest for a specific subset of tools, annotating credential status.
     * Tools that require credentials will show which are available (✓) or missing (✗).
     */
    public String generateManifest(Collection<Tool> subset, List<String> availableCredentials) {
        return subset.stream()
                .sorted(Comparator.comparing(Tool::name))
                .map(t -> formatToolEntry(t, availableCredentials))
                .collect(Collectors.joining("\n"));
    }

    private String formatToolEntry(Tool tool, List<String> availableCredentials) {
        var sb = new StringBuilder();
        sb.append(tool.name()).append(": ").append(tool.description());

        if (tool.requiresNetwork()) sb.append(" [net]");
        if (tool.hasSideEffects()) sb.append(" [se]");

        List<String> creds = tool.requiredCredentials();
        if (!creds.isEmpty()) {
            sb.append(" [cred:");
            for (String key : creds) {
                sb.append(" ").append(key);
                sb.append(availableCredentials.contains(key) ? "\u2713" : "\u2717");
            }
            sb.append("]");
        }

        Map<String, ToolParam> schema = tool.inputSchema();
        if (schema != null && !schema.isEmpty()) {
            sb.append("\n ");
            boolean first = true;
            for (var entry : schema.entrySet()) {
                if (!first) sb.append(" | ");
                first = false;
                sb.append(entry.getKey());
                ToolParam param = entry.getValue();
                if (param.required()) sb.append("*");
                sb.append("(").append(param.type()).append("): ").append(param.description());
            }
        }

        return sb.toString();
    }
}
