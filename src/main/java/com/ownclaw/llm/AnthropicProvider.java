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

        // Claude: system prompt is a top-level field, not in messages
        String systemPrompt = null;
        ArrayNode msgs = body.putArray("messages");
        for (LlmMessage msg : messages) {
            if (msg.role() == LlmMessage.Role.SYSTEM) {
                // Accumulate system messages (there should be only one, but handle multiples)
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
            body.put("system", systemPrompt);
        }

        // Claude doesn't have a response_format: json_object option.
        // JSON mode is enforced via prompt engineering (ThinkingEngine already says
        // "respond with valid JSON"). We can add a prefill to help:
        if (reqConfig.jsonMode()) {
            // Add assistant prefill to steer toward JSON output
            ObjectNode prefill = msgs.addObject();
            prefill.put("role", "assistant");
            prefill.put("content", "{");
        }

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

            // If we used JSON prefill, prepend the "{" back
            if (reqConfig.jsonMode()) {
                content = "{" + content;
            }

            int promptTokens = json.path("usage").path("input_tokens").asInt(0);
            int completionTokens = json.path("usage").path("output_tokens").asInt(0);

            log.debug("Anthropic [{}]: {} input + {} output tokens", model, promptTokens, completionTokens);
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
