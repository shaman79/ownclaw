package com.ownclaw.agent.tools.impl;

import com.ownclaw.agent.tools.*;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Makes HTTP requests to arbitrary URLs.
 * Supports GET, POST, PUT, DELETE, PATCH, HEAD methods.
 */
@Component
public class HttpRequestTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(HttpRequestTool.class);
    private static final int MAX_RESPONSE_LENGTH = 100_000;

    private final OkHttpClient httpClient;

    public HttpRequestTool() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }

    @Override
    public String name() { return "http_request"; }

    @Override
    public String description() {
        return "Make an HTTP request to a URL and return the response. " +
                "Supports GET, POST, PUT, DELETE, PATCH, HEAD methods.";
    }

    @Override
    public Map<String, ToolParam> inputSchema() {
        var schema = new LinkedHashMap<String, ToolParam>();
        schema.put("url", ToolParam.required("string", "The URL to request"));
        schema.put("method", ToolParam.optional("string", "HTTP method (default: GET)"));
        schema.put("body", ToolParam.optional("string", "Request body (for POST/PUT/PATCH)"));
        schema.put("content_type", ToolParam.optional("string", "Content-Type header (default: application/json)"));
        schema.put("headers", ToolParam.optional("object", "Additional headers as key-value pairs"));
        return schema;
    }

    @Override
    public boolean requiresNetwork() { return true; }

    @Override
    public int estimatedMaxDurationSeconds() { return 60; }

    @Override
    public ToolResult execute(Map<String, Object> params, ToolExecutionContext context) {
        String url = (String) params.get("url");
        if (url == null || url.isBlank()) {
            return ToolResult.failure("No URL provided.");
        }

        String method = params.containsKey("method") ? params.get("method").toString().toUpperCase() : "GET";
        String body = params.containsKey("body") ? params.get("body").toString() : null;
        String contentType = params.containsKey("content_type")
                ? params.get("content_type").toString() : "application/json";

        try {
            Request.Builder requestBuilder = new Request.Builder().url(url);

            // Add custom headers
            if (params.get("headers") instanceof Map<?, ?> headers) {
                for (var entry : headers.entrySet()) {
                    requestBuilder.addHeader(entry.getKey().toString(), entry.getValue().toString());
                }
            }

            // Set method and body
            RequestBody requestBody = body != null
                    ? RequestBody.create(body, MediaType.parse(contentType))
                    : null;

            switch (method) {
                case "GET" -> requestBuilder.get();
                case "POST" -> requestBuilder.post(requestBody != null ? requestBody : RequestBody.create("", null));
                case "PUT" -> requestBuilder.put(requestBody != null ? requestBody : RequestBody.create("", null));
                case "DELETE" -> {
                    if (requestBody != null) requestBuilder.delete(requestBody);
                    else requestBuilder.delete();
                }
                case "PATCH" -> requestBuilder.patch(requestBody != null ? requestBody : RequestBody.create("", null));
                case "HEAD" -> requestBuilder.head();
                default -> { return ToolResult.failure("Unsupported HTTP method: " + method); }
            }

            try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
                int statusCode = response.code();
                String responseBody = response.body() != null ? response.body().string() : "";

                if (responseBody.length() > MAX_RESPONSE_LENGTH) {
                    responseBody = responseBody.substring(0, MAX_RESPONSE_LENGTH) + "\n...[response truncated]";
                }

                Map<String, Object> structured = new LinkedHashMap<>();
                structured.put("status_code", statusCode);
                structured.put("content_type", response.header("Content-Type", ""));
                structured.put("content_length", responseBody.length());

                if (statusCode >= 200 && statusCode < 400) {
                    return ToolResult.success(
                            "HTTP " + statusCode + " " + response.message() + "\n\n" + responseBody,
                            structured
                    );
                } else {
                    return ToolResult.failure(
                            "HTTP " + statusCode + " " + response.message() + "\n\n" + responseBody,
                            structured
                    );
                }
            }
        } catch (IOException e) {
            log.error("HTTP request failed for {}: {}", url, e.getMessage());
            return ToolResult.failure("HTTP request failed: " + e.getMessage());
        }
    }
}
