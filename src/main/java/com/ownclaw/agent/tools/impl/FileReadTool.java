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
 * Reads file contents from the filesystem.
 * Supports text files with optional line range selection.
 */
@Component
public class FileReadTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(FileReadTool.class);
    private static final int MAX_FILE_SIZE = 500_000; // ~500KB

    @Override
    public String name() { return "file_read"; }

    @Override
    public String description() {
        return "Read the contents of a file from the filesystem. " +
                "Returns the file content as text. Supports optional line range selection.";
    }

    @Override
    public Map<String, ToolParam> inputSchema() {
        var schema = new LinkedHashMap<String, ToolParam>();
        schema.put("path", ToolParam.required("string", "Absolute or relative path to the file"));
        schema.put("start_line", ToolParam.optional("integer", "First line to read (1-based, inclusive)"));
        schema.put("end_line", ToolParam.optional("integer", "Last line to read (1-based, inclusive)"));
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> params, ToolExecutionContext context) {
        String pathStr = (String) params.get("path");
        if (pathStr == null || pathStr.isBlank()) {
            return ToolResult.failure("No file path provided.");
        }

        Path path = Path.of(pathStr);
        if (!Files.exists(path)) {
            return ToolResult.failure("File not found: " + pathStr);
        }
        if (!Files.isRegularFile(path)) {
            return ToolResult.failure("Path is not a regular file: " + pathStr);
        }

        try {
            long fileSize = Files.size(path);
            if (fileSize > MAX_FILE_SIZE) {
                return ToolResult.failure("File is too large (" + fileSize + " bytes). Maximum: " +
                        MAX_FILE_SIZE + " bytes. Use start_line/end_line to read a portion.");
            }

            String content = Files.readString(path, StandardCharsets.UTF_8);
            String[] lines = content.split("\n", -1);

            // Apply line range
            Integer startLine = toInt(params.get("start_line"));
            Integer endLine = toInt(params.get("end_line"));

            if (startLine != null || endLine != null) {
                int start = (startLine != null ? startLine : 1) - 1; // convert to 0-based
                int end = (endLine != null ? endLine : lines.length);
                start = Math.max(0, start);
                end = Math.min(lines.length, end);

                if (start >= end) {
                    return ToolResult.failure("Invalid line range: start=" + (start + 1) + " end=" + end);
                }

                StringBuilder sb = new StringBuilder();
                for (int i = start; i < end; i++) {
                    sb.append(lines[i]);
                    if (i < end - 1) sb.append("\n");
                }
                content = sb.toString();

                return ToolResult.success(content, Map.of(
                        "path", path.toAbsolutePath().toString(),
                        "total_lines", lines.length,
                        "lines_returned", end - start,
                        "start_line", start + 1,
                        "end_line", end
                ));
            }

            return ToolResult.success(content, Map.of(
                    "path", path.toAbsolutePath().toString(),
                    "total_lines", lines.length,
                    "size_bytes", fileSize
            ));
        } catch (IOException e) {
            log.error("Failed to read file {}: {}", pathStr, e.getMessage());
            return ToolResult.failure("Failed to read file: " + e.getMessage());
        }
    }

    private Integer toInt(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(value.toString()); } catch (Exception e) { return null; }
    }
}
