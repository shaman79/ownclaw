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
 * Ollama REST API provider for local LLM inference (Executor + SkillRunner).
 * <p>
 * API: POST {url}/api/chat with {"model", "messages", "stream": false, "options": {"temperature"}}
 */
@Component
public class OllamaProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaProvider.class);
    private static final MediaType JSON = MediaType.get("application/json");

    private final OwnClawConfig.Executor config;
    private final ObjectMapper mapper;
    private final OkHttpClient httpClient;

    public OllamaProvider(OwnClawConfig ownClawConfig, ObjectMapper mapper) {
        this.config = ownClawConfig.getExecutor();
        this.mapper = mapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)  // local inference can be slow
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public LlmResponse chat(List<LlmMessage> messages, LlmRequestConfig reqConfig) {
        String model = reqConfig.model() != null ? reqConfig.model() : config.getModel();
        double temperature = reqConfig.temperature() != null ? reqConfig.temperature() : config.getTemperature();

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("stream", false);

        ObjectNode options = body.putObject("options");
        options.put("temperature", temperature);
        if (config.getContextWindow() > 0) {
            options.put("num_ctx", config.getContextWindow());
        }
        if (reqConfig.maxTokens() != null) {
            options.put("num_predict", reqConfig.maxTokens());
        }

        ArrayNode msgs = body.putArray("messages");
        for (LlmMessage msg : messages) {
            ObjectNode m = msgs.addObject();
            m.put("role", msg.role().apiValue());
            m.put("content", msg.content());
        }

        String url = config.getUrl().replaceAll("/+$", "") + "/api/chat";
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errBody = response.body() != null ? response.body().string() : "";
                throw new LlmException("ollama", "HTTP " + response.code() + ": " + errBody,
                        response.code(), null);
            }

            JsonNode json = mapper.readTree(response.body().string());
            String content = json.path("message").path("content").asText("");
            int promptTokens = json.path("prompt_eval_count").asInt(0);
            int completionTokens = json.path("eval_count").asInt(0);
            long promptDurationNs = json.path("prompt_eval_duration").asLong(0);
            long evalDurationNs = json.path("eval_duration").asLong(0);

            if (completionTokens > 0 && evalDurationNs > 0) {
                double tps = completionTokens / (evalDurationNs / 1_000_000_000.0);
                if (promptTokens > 0 && promptDurationNs > 0) {
                    double ptps = promptTokens / (promptDurationNs / 1_000_000_000.0);
                    log.debug("Ollama [{}]: {} prompt ({} tok/s) + {} completion ({} tok/s)",
                            model,
                            promptTokens,
                            String.format(java.util.Locale.US, "%.1f", ptps),
                            completionTokens,
                            String.format(java.util.Locale.US, "%.1f", tps));
                } else {
                    log.debug("Ollama [{}]: {} prompt + {} completion ({} tok/s)",
                            model,
                            promptTokens,
                            completionTokens,
                            String.format(java.util.Locale.US, "%.1f", tps));
                }
            } else {
                log.debug("Ollama [{}]: {} prompt + {} completion tokens", model, promptTokens, completionTokens);
            }
            return new LlmResponse(content, promptTokens, completionTokens);

        } catch (IOException e) {
            throw new LlmException("ollama", "Connection failed: " + e.getMessage(), 0, e);
        }
    }

    @Override
    public boolean isAvailable() {
        String url = config.getUrl().replaceAll("/+$", "") + "/api/tags";
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            return response.isSuccessful();
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public String name() {
        return "ollama";
    }
}
