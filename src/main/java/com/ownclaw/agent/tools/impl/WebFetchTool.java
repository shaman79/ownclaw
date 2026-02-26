package com.ownclaw.agent.tools.impl;

import com.ownclaw.agent.tools.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fetches a web page and extracts its textual content.
 * Uses Jsoup for HTML parsing and text extraction, providing clean readable content
 * rather than raw HTML.
 */
@Component
public class WebFetchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebFetchTool.class);
    private static final int DEFAULT_MAX_LENGTH = 50_000;
    private static final int CONNECT_TIMEOUT_MS = 15_000;

    @Override
    public String name() { return "web_fetch"; }

    @Override
    public String description() {
        return "Fetch a web page and extract its readable text content. " +
                "Returns cleaned text without HTML tags. Supports pagination via offset/max_length " +
                "to retrieve large pages in chunks. Optionally extracts specific CSS selectors.";
    }

    @Override
    public Map<String, ToolParam> inputSchema() {
        var schema = new LinkedHashMap<String, ToolParam>();
        schema.put("url", ToolParam.required("string", "The URL to fetch"));
        schema.put("selector", ToolParam.optional("string",
                "CSS selector to extract specific content (e.g., 'article', 'main', '.content'). " +
                "If omitted, extracts all body text."));
        schema.put("include_links", ToolParam.optional("boolean",
                "Whether to include link URLs in the output (default: false)"));
        schema.put("offset", ToolParam.optional("integer",
                "Character offset to start reading from (default: 0). " +
                "Use this to paginate through large pages when previous output was truncated."));
        schema.put("max_length", ToolParam.optional("integer",
                "Maximum number of characters to return (default: 50000). " +
                "Use a smaller value for focused extraction, or set higher if needed."));
        return schema;
    }

    @Override
    public boolean requiresNetwork() { return true; }

    @Override
    public int estimatedMaxDurationSeconds() { return 30; }

    @Override
    public ToolResult execute(Map<String, Object> params, ToolExecutionContext context) {
        String url = (String) params.get("url");
        if (url == null || url.isBlank()) {
            return ToolResult.failure("No URL provided.");
        }

        String selector = params.containsKey("selector") ? params.get("selector").toString() : null;
        boolean includeLinks = Boolean.TRUE.equals(params.get("include_links"));
        int offset = toInt(params.get("offset"), 0);
        int maxLength = toInt(params.get("max_length"), DEFAULT_MAX_LENGTH);
        if (maxLength <= 0) maxLength = DEFAULT_MAX_LENGTH;
        if (offset < 0) offset = 0;

        try {
            Document doc = Jsoup.connect(url)
                    .timeout(CONNECT_TIMEOUT_MS)
                    .userAgent("OwnClaw Agent/1.0")
                    .followRedirects(true)
                    .get();

            String title = doc.title();
            String fullText;

            if (selector != null && !selector.isBlank()) {
                var elements = doc.select(selector);
                if (elements.isEmpty()) {
                    return ToolResult.failure("No elements matched selector '" + selector + "' on page: " + url);
                }
                fullText = elements.text();
            } else {
                fullText = doc.body() != null ? doc.body().text() : "";
            }

            int totalLength = fullText.length();

            // Apply pagination window
            String text;
            boolean hasMore;
            if (offset >= totalLength) {
                text = "";
                hasMore = false;
            } else {
                int end = Math.min(offset + maxLength, totalLength);
                text = fullText.substring(offset, end);
                hasMore = end < totalLength;
            }

            // Build output
            StringBuilder output = new StringBuilder();
            output.append("Title: ").append(title).append("\n");
            output.append("URL: ").append(url).append("\n");
            output.append("Content length: ").append(totalLength).append(" chars");
            if (offset > 0 || hasMore) {
                output.append(" | Showing: ").append(offset).append("-").append(offset + text.length())
                        .append(" of ").append(totalLength);
            }
            output.append("\n\n");
            output.append(text);

            if (hasMore) {
                int remaining = totalLength - (offset + text.length());
                output.append("\n\n...[").append(remaining)
                        .append(" more chars — use offset=").append(offset + text.length())
                        .append(" to continue reading]");
            }

            if (includeLinks) {
                var links = doc.select("a[href]");
                if (!links.isEmpty()) {
                    output.append("\n\n--- Links ---\n");
                    int count = 0;
                    for (var link : links) {
                        if (count >= 50) {
                            output.append("...[").append(links.size() - 50).append(" more links]\n");
                            break;
                        }
                        String href = link.absUrl("href");
                        String linkText = link.text().strip();
                        if (!href.isBlank() && !linkText.isBlank()) {
                            output.append(linkText).append(": ").append(href).append("\n");
                            count++;
                        }
                    }
                }
            }

            Map<String, Object> structured = Map.of(
                    "title", title,
                    "url", url,
                    "total_length", totalLength,
                    "offset", offset,
                    "returned_length", text.length(),
                    "has_more", hasMore
            );

            return ToolResult.success(output.toString(), structured);
        } catch (Exception e) {
            log.error("Web fetch failed for {}: {}", url, e.getMessage());
            return ToolResult.failure("Failed to fetch page: " + e.getMessage());
        }
    }

    /** Safely parse an integer from a param value (may be Number, String, or null). */
    private int toInt(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString().strip());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
