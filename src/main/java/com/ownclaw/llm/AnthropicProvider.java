package com.ownclaw.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ownclaw.config.OwnClawConfig;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Anthropic Messages API provider for cloud LLM inference.
 * <p>
 * API: POST https://api.anthropic.com/v1/messages
 * <p>
 * Claude uses a different message format than OpenAI:
 * - System prompt is a top-level field, NOT in the messages array
 * - No response_format: json_object — use prompt engineering for JSON mode
 * - Uses max_tokens instead of max_completion_tokens
 * - Returns usage.input_tokens / usage.output_tokens
 */
@Component
public class AnthropicProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicProvider.class);
    private static final MediaType JSON_TYPE = MediaType.get("application/json");
    private static final String BASE_URL = "https://api.anthropic.com/v1";
    private static final String API_VERSION = "2023-06-01";

    private final OwnClawConfig.Mentor config;
    private final ObjectMapper mapper;
    private final OkHttpClient httpClient;

    public AnthropicProvider(OwnClawConfig ownClawConfig, ObjectMapper mapper) {
        this.config = ownClawConfig.getMentor();
        this.mapper = mapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public LlmResponse chat(List<LlmMessage> messages, LlmRequestConfig reqConfig) {
        return RateLimitBackoff.execute(() -> chatInternal(messages, reqConfig), "anthropic");
    }

    private LlmResponse chatInternal(List<LlmMessage> messages, LlmRequestConfig reqConfig) {
        String apiKey = config.getAnthropicApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException("anthropic", "API key not configured");
        }

        String model = reqConfig.model() != null ? reqConfig.model() : config.getAnthropicModel();
        double temperature = reqConfig.temperature() != null ? reqConfig.temperature() : config.getTemperature();
        int maxTokens = reqConfig.maxTokens() != null ? reqConfig.maxTokens() : 4096;

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);

        // Claude: system prompt is a top-level field, not in messages.
        // We use structured content blocks with cache_control to enable prompt caching.
        String systemPrompt = null;
        ArrayNode msgs = body.putArray("messages");
        for (LlmMessage msg : messages) {
            if (msg.role() == LlmMessage.Role.SYSTEM) {
                systemPrompt = (systemPrompt == null)
                        ? msg.content()
                        : systemPrompt + "\n\n" + msg.content();
            } else {
                ObjectNode m = msgs.addObject();
                m.put("role", msg.role().apiValue());
                m.put("content", msg.content());
            }
        }
        if (systemPrompt != null) {
            // Split system prompt at the cache boundary marker.
            // Everything BEFORE the marker is static (rules, guidelines) and cacheable.
            // Everything AFTER is dynamic (datetime, tools, user prefs) and changes per request.
            ArrayNode systemArray = body.putArray("system");
            String marker = "\n<!-- CACHE_BOUNDARY -->\n";
            int markerIdx = systemPrompt.indexOf(marker);
            if (markerIdx > 0) {
                // Static part — cached across requests
                ObjectNode staticBlock = systemArray.addObject();
                staticBlock.put("type", "text");
                staticBlock.put("text", systemPrompt.substring(0, markerIdx));
                staticBlock.putObject("cache_control").put("type", "ephemeral");
                // Dynamic part — changes every request, not cached
                ObjectNode dynamicBlock = systemArray.addObject();
                dynamicBlock.put("type", "text");
                dynamicBlock.put("text", systemPrompt.substring(markerIdx + marker.length()));
            } else {
                // No marker found — cache the whole thing (fallback)
                ObjectNode sysBlock = systemArray.addObject();
                sysBlock.put("type", "text");
                sysBlock.put("text", systemPrompt);
                sysBlock.putObject("cache_control").put("type", "ephemeral");
            }
        }

        // Cache conversation history: mark the second-to-last message so the
        // prefix (everything before the latest turn) is cached between steps.
        if (msgs.size() >= 2) {
            ObjectNode prefixMsg = (ObjectNode) msgs.get(msgs.size() - 2);
            String rawContent = prefixMsg.path("content").asText("");
            // Convert plain string content to content-block array with cache_control
            prefixMsg.remove("content");
            ArrayNode contentArray = prefixMsg.putArray("content");
            ObjectNode block = contentArray.addObject();
            block.put("type", "text");
            block.put("text", rawContent);
            block.putObject("cache_control").put("type", "ephemeral");
        }

        // Claude doesn't have a response_format: json_object option.
        // JSON mode is enforced via prompt engineering (ThinkingEngine already says
        // "respond with valid JSON"). Assistant prefill is NOT used because some
        // Claude models reject it with HTTP 400.

        Request request = new Request.Builder()
                .url(BASE_URL + "/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build();

        try (Response response = clientForRequest(reqConfig).newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                int code = response.code();
                // Anthropic uses 429 for rate limits, 401 for auth — same as OpenAI
                throw new LlmException("anthropic", "HTTP " + code + ": " + responseBody,
                        code, null);
            }

            JsonNode json = mapper.readTree(responseBody);

            // Extract text content from the response
            // Anthropic returns: { content: [ { type: "text", text: "..." } ], usage: {...} }
            StringBuilder contentBuilder = new StringBuilder();
            JsonNode contentArray = json.path("content");
            if (contentArray.isArray()) {
                for (JsonNode block : contentArray) {
                    if ("text".equals(block.path("type").asText())) {
                        contentBuilder.append(block.path("text").asText(""));
                    }
                }
            }
            String content = contentBuilder.toString();

            int promptTokens = json.path("usage").path("input_tokens").asInt(0);
            int completionTokens = json.path("usage").path("output_tokens").asInt(0);
            int cacheCreation = json.path("usage").path("cache_creation_input_tokens").asInt(0);
            int cacheRead = json.path("usage").path("cache_read_input_tokens").asInt(0);

            if (cacheRead > 0 || cacheCreation > 0) {
                log.info("Anthropic [{}]: {} input + {} output tokens (cache: {} created, {} read)",
                        model, promptTokens, completionTokens, cacheCreation, cacheRead);
            } else {
                log.debug("Anthropic [{}]: {} input + {} output tokens", model, promptTokens, completionTokens);
            }
            return new LlmResponse(content, promptTokens, completionTokens);

        } catch (IOException e) {
            throw new LlmException("anthropic", "Connection failed: " + e.getMessage(), 0, e);
        }
    }

    @Override
    public boolean isAvailable() {
        String apiKey = config.getAnthropicApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        // Anthropic doesn't have a /models endpoint like OpenAI.
        // Use a minimal messages call to verify the key works.
        // Actually, just check if the key is set — actual validation
        // happens on first use to avoid unnecessary API calls.
        return true;
    }

    @Override
    public String name() {
        return "anthropic";
    }

    private OkHttpClient clientForRequest(LlmRequestConfig reqConfig) {
        if (reqConfig.readTimeoutSec() != null && reqConfig.readTimeoutSec() > 0) {
            return httpClient.newBuilder()
                    .readTimeout(reqConfig.readTimeoutSec(), TimeUnit.SECONDS)
                    .build();
        }
        return httpClient;
    }
}
