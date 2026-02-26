package com.ownclaw.agent.tools.impl;

import com.ownclaw.agent.tools.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Stream;

/**
 * Lists directory contents and searches for files by pattern.
 * Provides filesystem navigation capabilities to the agent.
 */
@Component
public class FileListTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(FileListTool.class);
    private static final int MAX_RESULTS = 200;

    @Override
    public String name() { return "file_list"; }

    @Override
    public String description() {
        return "List files and directories at a given path. " +
                "Optionally filter by glob pattern and recurse into subdirectories.";
    }

    @Override
    public Map<String, ToolParam> inputSchema() {
        var schema = new LinkedHashMap<String, ToolParam>();
        schema.put("path", ToolParam.required("string", "Directory path to list"));
        schema.put("pattern", ToolParam.optional("string",
                "Glob pattern to filter results (e.g., '*.txt', '**/*.java')"));
        schema.put("recursive", ToolParam.optional("boolean", "Whether to recurse into subdirectories (default: false)"));
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> params, ToolExecutionContext context) {
        String pathStr = (String) params.get("path");
        if (pathStr == null || pathStr.isBlank()) {
            return ToolResult.failure("No path provided.");
        }

        Path dir = Path.of(pathStr);
        if (!Files.exists(dir)) {
            return ToolResult.failure("Path not found: " + pathStr);
        }
        if (!Files.isDirectory(dir)) {
            return ToolResult.failure("Path is not a directory: " + pathStr);
        }

        String pattern = params.containsKey("pattern") ? params.get("pattern").toString() : null;
        boolean recursive = Boolean.TRUE.equals(params.get("recursive"));

        try {
            List<String> entries = new ArrayList<>();

            if (pattern != null && !pattern.isBlank()) {
                // Glob search
                String globPattern = recursive ? "glob:" + pattern : "glob:" + pattern;
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

                try (Stream<Path> stream = recursive ? Files.walk(dir) : Files.list(dir)) {
                    stream.filter(p -> matcher.matches(dir.relativize(p)) || matcher.matches(p.getFileName()))
                            .limit(MAX_RESULTS)
                            .forEach(p -> entries.add(formatEntry(dir, p)));
                }
            } else {
                // Simple listing
                try (Stream<Path> stream = recursive ? Files.walk(dir) : Files.list(dir)) {
                    stream.filter(p -> !p.equals(dir))
                            .limit(MAX_RESULTS)
                            .forEach(p -> entries.add(formatEntry(dir, p)));
                }
            }

            if (entries.isEmpty()) {
                return ToolResult.success("Directory is empty" +
                        (pattern != null ? " (no files matching '" + pattern + "')" : "") +
                        ": " + pathStr,
                        Map.of("count", 0, "path", dir.toAbsolutePath().toString()));
            }

            String output = String.join("\n", entries);
            return ToolResult.success(output,
                    Map.of("count", entries.size(), "path", dir.toAbsolutePath().toString()));
        } catch (IOException e) {
            log.error("Failed to list directory {}: {}", pathStr, e.getMessage());
            return ToolResult.failure("Failed to list directory: " + e.getMessage());
        }
    }

    private String formatEntry(Path base, Path entry) {
        String relative = base.relativize(entry).toString();
        if (Files.isDirectory(entry)) {
            return relative + "/";
        }
        try {
            long size = Files.size(entry);
            return relative + " (" + formatSize(size) + ")";
        } catch (IOException e) {
            return relative;
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }
}
