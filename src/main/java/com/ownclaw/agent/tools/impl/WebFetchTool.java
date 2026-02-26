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
    private static final int MAX_TEXT_LENGTH = 50_000;
    private static final int CONNECT_TIMEOUT_MS = 15_000;

    @Override
    public String name() { return "web_fetch"; }

    @Override
    public String description() {
        return "Fetch a web page and extract its readable text content. " +
                "Returns cleaned text without HTML tags. Optionally extracts specific CSS selectors.";
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

        try {
            Document doc = Jsoup.connect(url)
                    .timeout(CONNECT_TIMEOUT_MS)
                    .userAgent("OwnClaw Agent/1.0")
                    .followRedirects(true)
                    .get();

            String title = doc.title();
            String text;

            if (selector != null && !selector.isBlank()) {
                var elements = doc.select(selector);
                if (elements.isEmpty()) {
                    return ToolResult.failure("No elements matched selector '" + selector + "' on page: " + url);
                }
                text = elements.text();
            } else {
                text = doc.body() != null ? doc.body().text() : "";
            }

            // Optionally extract links
            StringBuilder output = new StringBuilder();
            output.append("Title: ").append(title).append("\n");
            output.append("URL: ").append(url).append("\n\n");

            if (text.length() > MAX_TEXT_LENGTH) {
                text = text.substring(0, MAX_TEXT_LENGTH) + "\n...[content truncated]";
            }
            output.append(text);

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
                    "text_length", text.length()
            );

            return ToolResult.success(output.toString(), structured);
        } catch (Exception e) {
            log.error("Web fetch failed for {}: {}", url, e.getMessage());
            return ToolResult.failure("Failed to fetch page: " + e.getMessage());
        }
    }
}
