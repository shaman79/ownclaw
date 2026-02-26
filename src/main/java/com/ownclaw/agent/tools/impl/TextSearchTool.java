package com.ownclaw.agent.tools.impl;

import com.ownclaw.agent.tools.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Searches for text patterns within files.
 * Provides grep-like functionality for the agent to find information in codebases,
 * logs, or any text files.
 */
@Component
public class TextSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(TextSearchTool.class);
    private static final int MAX_MATCHES = 100;
    private static final int MAX_FILES = 1000;
    private static final int CONTEXT_LINES = 2;

    @Override
    public String name() { return "text_search"; }

    @Override
    public String description() {
        return "Search for text patterns within files in a directory. " +
                "Similar to grep — finds occurrences of a string or regex in files.";
    }

    @Override
    public Map<String, ToolParam> inputSchema() {
        var schema = new LinkedHashMap<String, ToolParam>();
        schema.put("pattern", ToolParam.required("string", "Text pattern to search for (plain text or regex)"));
        schema.put("path", ToolParam.required("string", "Directory or file path to search in"));
        schema.put("file_pattern", ToolParam.optional("string", "Glob pattern to filter files (e.g., '*.txt', '*.java')"));
        schema.put("case_sensitive", ToolParam.optional("boolean", "Whether the search is case sensitive (default: false)"));
        schema.put("regex", ToolParam.optional("boolean", "Whether the pattern is a regex (default: false)"));
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> params, ToolExecutionContext context) {
        String pattern = (String) params.get("pattern");
        String pathStr = (String) params.get("path");

        if (pattern == null || pattern.isBlank()) return ToolResult.failure("No search pattern provided.");
        if (pathStr == null || pathStr.isBlank()) return ToolResult.failure("No search path provided.");

        Path searchPath = Path.of(pathStr);
        if (!Files.exists(searchPath)) return ToolResult.failure("Path not found: " + pathStr);

        String filePattern = params.containsKey("file_pattern") ? params.get("file_pattern").toString() : null;
        boolean caseSensitive = Boolean.TRUE.equals(params.get("case_sensitive"));
        boolean isRegex = Boolean.TRUE.equals(params.get("regex"));

        java.util.regex.Pattern searchRegex;
        try {
            int flags = caseSensitive ? 0 : java.util.regex.Pattern.CASE_INSENSITIVE;
            searchRegex = isRegex
                    ? java.util.regex.Pattern.compile(pattern, flags)
                    : java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(pattern), flags);
        } catch (Exception e) {
            return ToolResult.failure("Invalid regex pattern: " + e.getMessage());
        }

        PathMatcher fileMatcher = filePattern != null
                ? FileSystems.getDefault().getPathMatcher("glob:" + filePattern)
                : null;

        try {
            List<String> matches = new ArrayList<>();
            int totalMatches = 0;
            int filesSearched = 0;

            List<Path> files = collectFiles(searchPath, fileMatcher);

            for (Path file : files) {
                if (totalMatches >= MAX_MATCHES) break;

                try {
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        if (searchRegex.matcher(lines.get(i)).find()) {
                            totalMatches++;
                            if (totalMatches <= MAX_MATCHES) {
                                String relativePath = Files.isDirectory(searchPath)
                                        ? searchPath.relativize(file).toString()
                                        : file.getFileName().toString();
                                matches.add(formatMatch(relativePath, lines, i));
                            }
                        }
                    }
                    filesSearched++;
                } catch (Exception e) {
                    // Skip binary or unreadable files
                }
            }

            if (matches.isEmpty()) {
                return ToolResult.success("No matches found for '" + pattern + "' in " + pathStr,
                        Map.of("total_matches", 0, "files_searched", filesSearched));
            }

            StringBuilder output = new StringBuilder();
            output.append("Found ").append(totalMatches).append(" match(es):\n\n");
            output.append(String.join("\n---\n", matches));

            if (totalMatches > MAX_MATCHES) {
                output.append("\n\n...[showing first ").append(MAX_MATCHES)
                        .append(" of ").append(totalMatches).append(" matches]");
            }

            return ToolResult.success(output.toString(),
                    Map.of("total_matches", totalMatches, "files_searched", filesSearched));
        } catch (IOException e) {
            log.error("Text search failed: {}", e.getMessage());
            return ToolResult.failure("Search failed: " + e.getMessage());
        }
    }

    private List<Path> collectFiles(Path path, PathMatcher fileMatcher) throws IOException {
        if (Files.isRegularFile(path)) {
            return List.of(path);
        }

        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(path)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> fileMatcher == null || fileMatcher.matches(p.getFileName()))
                    .limit(MAX_FILES)
                    .forEach(files::add);
        }
        return files;
    }

    private String formatMatch(String filePath, List<String> lines, int matchLine) {
        var sb = new StringBuilder();
        sb.append(filePath).append(":").append(matchLine + 1).append("\n");

        int start = Math.max(0, matchLine - CONTEXT_LINES);
        int end = Math.min(lines.size(), matchLine + CONTEXT_LINES + 1);

        for (int i = start; i < end; i++) {
            String prefix = (i == matchLine) ? ">> " : "   ";
            sb.append(prefix).append(String.format("%4d", i + 1)).append("| ").append(lines.get(i)).append("\n");
        }

        return sb.toString();
    }
}
