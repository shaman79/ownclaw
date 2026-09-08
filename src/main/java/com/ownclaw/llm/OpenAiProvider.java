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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenAI REST API provider for cloud LLM inference (Mentor).
 * <p>
 * API: POST https://api.openai.com/v1/chat/completions
 * <p>
 * Reasoning models reject sampling parameters — see {@link #supportsSampling(String)}.
 */
@Component
public class OpenAiProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiProvider.class);
    private static final MediaType JSON_TYPE = MediaType.get("application/json");
    private static final String BASE_URL = "https://api.openai.com/v1";

    /**
     * Fallback output budget when the caller doesn't set one. Set well above the old
     * 4096 because on reasoning models {@code max_completion_tokens} also has to cover
     * the hidden reasoning tokens — a tight ceiling burns the whole budget on reasoning
     * and returns empty or truncated content. 16k still returns comfortably inside the
     * non-streaming read timeout.
     */
    private static final int DEFAULT_MAX_TOKENS = 16000;

    /** The {@code o<n>} reasoning family: o1, o3-mini, o4-mini-2025-04-16, ... */
    private static final Pattern REASONING_FAMILY = Pattern.compile("^o\\d");

    /** The {@code gpt-<major>} generation, e.g. gpt-4o -> 4, gpt-5-mini -> 5. */
    private static final Pattern GPT_GENERATION = Pattern.compile("^gpt-(\\d+)");

    private final OwnClawConfig.Mentor config;
    private final ObjectMapper mapper;
    private final OkHttpClient httpClient;

    public OpenAiProvider(OwnClawConfig ownClawConfig, ObjectMapper mapper) {
        this.config = ownClawConfig.getMentor();
        this.mapper = mapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)  // code generation can be slow
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public LlmResponse chat(List<LlmMessage> messages, LlmRequestConfig reqConfig) {
        return RateLimitBackoff.execute(() -> chatInternal(messages, reqConfig), "openai");
    }

    private LlmResponse chatInternal(List<LlmMessage> messages, LlmRequestConfig reqConfig) {
        String apiKey = config.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException("openai", "API key not configured");
        }

        String model = reqConfig.model() != null ? reqConfig.model() : config.getModel();
        int maxTokens = reqConfig.maxTokens() != null ? reqConfig.maxTokens() : DEFAULT_MAX_TOKENS;

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_completion_tokens", maxTokens);

        // Reasoning models pick their own sampling and reject any explicit value with
        // HTTP 400 "Unsupported value: 'temperature' ... Only the default (1) is supported."
        if (supportsSampling(model)) {
            double temperature = reqConfig.temperature() != null ? reqConfig.temperature() : config.getTemperature();
            body.put("temperature", temperature);
        } else {
            log.debug("OpenAI [{}]: omitting temperature, not supported by this model", model);
        }

        if (reqConfig.jsonMode()) {
            body.putObject("response_format").put("type", "json_object");
        }

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

        try (Response response = clientForRequest(reqConfig).newCall(request).execute()) {
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

    /**
     * Whether a model still accepts sampling parameters (temperature / top_p).
     * <p>
     * OpenAI's reasoning models control their own sampling: passing an explicit
     * {@code temperature} fails with HTTP 400
     * {@code "Unsupported value: 'temperature' does not support 0.4 with this model.
     * Only the default (1) is supported."}
     * <p>
     * Not accepted by: the {@code o<n>} family (o1, o3, o4-mini, ...) and gpt-5 and newer.
     * Still accepted by: gpt-4o, gpt-4.1, gpt-4-turbo, gpt-3.5 and the like.
     * The check is family/version-based rather than a hardcoded list so that models
     * released later default to the correct behaviour — and it errs towards leaving the
     * parameter out, since an ignored temperature is harmless while an extra one is a
     * hard 400.
     */
    static boolean supportsSampling(String model) {
        if (model == null || model.isBlank()) {
            return true;
        }
        String id = model.trim().toLowerCase();
        if (REASONING_FAMILY.matcher(id).find()) {
            return false;
        }
        Matcher m = GPT_GENERATION.matcher(id);
        if (m.find() && Integer.parseInt(m.group(1)) >= 5) {
            return false;
        }
        return true;
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

    /**
     * Return an OkHttpClient with the read timeout from reqConfig (if set),
     * otherwise use the default httpClient. Uses newBuilder() so the
     * connection pool and dispatcher are shared.
     */
    private OkHttpClient clientForRequest(LlmRequestConfig reqConfig) {
        if (reqConfig.readTimeoutSec() != null && reqConfig.readTimeoutSec() > 0) {
            return httpClient.newBuilder()
                    .readTimeout(reqConfig.readTimeoutSec(), TimeUnit.SECONDS)
                    .build();
        }
        return httpClient;
    }
}
