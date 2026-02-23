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
 * OpenAI REST API provider for cloud LLM inference (Mentor).
 * <p>
 * API: POST https://api.openai.com/v1/chat/completions
 */
@Component
public class OpenAiProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiProvider.class);
    private static final MediaType JSON_TYPE = MediaType.get("application/json");
    private static final String BASE_URL = "https://api.openai.com/v1";

    private final OwnClawConfig.Mentor config;
    private final ObjectMapper mapper;
    private final OkHttpClient httpClient;

    public OpenAiProvider(OwnClawConfig ownClawConfig, ObjectMapper mapper) {
        this.config = ownClawConfig.getMentor();
        this.mapper = mapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public LlmResponse chat(List<LlmMessage> messages, LlmRequestConfig reqConfig) {
        String apiKey = config.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException("openai", "API key not configured");
        }

        String model = reqConfig.model() != null ? reqConfig.model() : config.getModel();
        double temperature = reqConfig.temperature() != null ? reqConfig.temperature() : config.getTemperature();
        int maxTokens = reqConfig.maxTokens() != null ? reqConfig.maxTokens() : 4096;

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);

        ArrayNode msgs = body.putArray("messages");
        for (LlmMessage msg : messages) {
            ObjectNode m = msgs.addObject();
            m.put("role", msg.role().apiValue());
            m.put("content", msg.content());
        }

        Request request = new Request.Builder()
                .url(BASE_URL + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                throw new LlmException("openai", "HTTP " + response.code() + ": " + responseBody,
                        response.code(), null);
            }

            JsonNode json = mapper.readTree(responseBody);
            String content = json.path("choices").path(0)
                    .path("message").path("content").asText("");
            int promptTokens = json.path("usage").path("prompt_tokens").asInt(0);
            int completionTokens = json.path("usage").path("completion_tokens").asInt(0);

            log.debug("OpenAI [{}]: {} prompt + {} completion tokens", model, promptTokens, completionTokens);
            return new LlmResponse(content, promptTokens, completionTokens);

        } catch (IOException e) {
            throw new LlmException("openai", "Connection failed: " + e.getMessage(), 0, e);
        }
    }

    @Override
    public boolean isAvailable() {
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            return false;
        }
        Request request = new Request.Builder()
                .url(BASE_URL + "/models")
                .header("Authorization", "Bearer " + config.getApiKey())
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            return response.isSuccessful();
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public String name() {
        return "openai";
    }
}
