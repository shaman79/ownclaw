package com.ownclaw.agent.tools.impl;

import com.ownclaw.agent.tools.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes content to files on the filesystem.
 * Supports creating, overwriting, and appending to files.
 */
@Component
public class FileWriteTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(FileWriteTool.class);

    @Override
    public String name() { return "file_write"; }

    @Override
    public String description() {
        return "Write content to a file. Creates the file (and parent directories) if they don't exist. " +
                "Can overwrite or append to existing files.";
    }

    @Override
    public Map<String, ToolParam> inputSchema() {
        var schema = new LinkedHashMap<String, ToolParam>();
        schema.put("path", ToolParam.required("string", "Absolute or relative path to the file"));
        schema.put("content", ToolParam.required("string", "Content to write to the file"));
        schema.put("append", ToolParam.optional("boolean", "If true, append to existing file instead of overwriting (default: false)"));
        return schema;
    }

    @Override
    public boolean hasSideEffects() { return true; }

    @Override
    public ToolResult execute(Map<String, Object> params, ToolExecutionContext context) {
        String pathStr = (String) params.get("path");
        if (pathStr == null || pathStr.isBlank()) {
            return ToolResult.failure("No file path provided.");
        }

        String content = params.containsKey("content") ? params.get("content").toString() : "";
        boolean append = Boolean.TRUE.equals(params.get("append"));

        Path path = Path.of(pathStr);

        try {
            // Ensure parent directories exist
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            if (append) {
                Files.writeString(path, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                Files.writeString(path, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }

            long size = Files.size(path);
            String action = append ? "appended to" : (Files.exists(path) ? "overwritten" : "created");

            return ToolResult.success(
                    "Successfully " + action + " file: " + path.toAbsolutePath() + " (" + size + " bytes)",
                    Map.of(
                            "path", path.toAbsolutePath().toString(),
                            "size_bytes", size,
                            "action", action
                    )
            );
        } catch (IOException e) {
            log.error("Failed to write file {}: {}", pathStr, e.getMessage());
            return ToolResult.failure("Failed to write file: " + e.getMessage());
        }
    }
}
