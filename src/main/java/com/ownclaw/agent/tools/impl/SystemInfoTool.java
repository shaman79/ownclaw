package com.ownclaw.agent.tools.impl;

import com.ownclaw.agent.tools.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provides system information to the agent.
 * This tool lets the agent understand the environment it's running in.
 */
@Component
public class SystemInfoTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SystemInfoTool.class);

    @Override
    public String name() { return "system_info"; }

    @Override
    public String description() {
        return "Get information about the host system (OS, architecture, environment variables, etc.).";
    }

    @Override
    public Map<String, ToolParam> inputSchema() {
        var schema = new LinkedHashMap<String, ToolParam>();
        schema.put("category", ToolParam.optional("string",
                "Category of info: 'os', 'env', 'java', 'disk', 'memory', or 'all' (default: 'all')"));
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> params, ToolExecutionContext context) {
        String category = params.containsKey("category") ? params.get("category").toString().toLowerCase() : "all";

        var sb = new StringBuilder();
        var structured = new LinkedHashMap<String, Object>();

        if ("all".equals(category) || "os".equals(category)) {
            sb.append("## Operating System\n");
            addProp(sb, structured, "os.name", System.getProperty("os.name"));
            addProp(sb, structured, "os.version", System.getProperty("os.version"));
            addProp(sb, structured, "os.arch", System.getProperty("os.arch"));
            addProp(sb, structured, "user.name", System.getProperty("user.name"));
            addProp(sb, structured, "user.home", System.getProperty("user.home"));
            addProp(sb, structured, "user.dir", System.getProperty("user.dir"));
            sb.append("\n");
        }

        if ("all".equals(category) || "java".equals(category)) {
            sb.append("## Java Runtime\n");
            addProp(sb, structured, "java.version", System.getProperty("java.version"));
            addProp(sb, structured, "java.vendor", System.getProperty("java.vendor"));
            addProp(sb, structured, "java.home", System.getProperty("java.home"));
            sb.append("\n");
        }

        if ("all".equals(category) || "memory".equals(category)) {
            Runtime rt = Runtime.getRuntime();
            long maxMem = rt.maxMemory();
            long totalMem = rt.totalMemory();
            long freeMem = rt.freeMemory();

            sb.append("## Memory\n");
            addProp(sb, structured, "max_memory_mb", maxMem / (1024 * 1024));
            addProp(sb, structured, "total_memory_mb", totalMem / (1024 * 1024));
            addProp(sb, structured, "free_memory_mb", freeMem / (1024 * 1024));
            addProp(sb, structured, "available_processors", rt.availableProcessors());
            sb.append("\n");
        }

        if ("all".equals(category) || "disk".equals(category)) {
            sb.append("## Disk\n");
            for (var root : java.io.File.listRoots()) {
                long total = root.getTotalSpace();
                long free = root.getFreeSpace();
                sb.append(root.getAbsolutePath())
                        .append(" — Total: ").append(total / (1024 * 1024 * 1024)).append("GB")
                        .append(", Free: ").append(free / (1024 * 1024 * 1024)).append("GB")
                        .append("\n");
            }
            sb.append("\n");
        }

        if ("env".equals(category)) {
            sb.append("## Environment Variables\n");
            // Only include safe, non-sensitive env vars
            Map<String, String> env = System.getenv();
            for (var entry : env.entrySet()) {
                String key = entry.getKey().toUpperCase();
                // Skip potentially sensitive variables
                if (key.contains("KEY") || key.contains("SECRET") || key.contains("TOKEN")
                        || key.contains("PASSWORD") || key.contains("CREDENTIAL")) {
                    continue;
                }
                sb.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
            }
        }

        return ToolResult.success(sb.toString(), structured);
    }

    private void addProp(StringBuilder sb, Map<String, Object> structured, String key, Object value) {
        sb.append("  ").append(key).append(": ").append(value).append("\n");
        structured.put(key, value);
    }
}
