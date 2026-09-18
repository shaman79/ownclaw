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
                .readTimeout(600, TimeUnit.SECONDS)  // local inference is slow, especially for code generation
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

        // Keep the model resident between calls.
        //
        // Nothing here set keep_alive, so every request inherited whatever the server default
        // happened to be. Ollama's own default is five minutes, and this deployment makes
        // local calls in bursts separated by much longer gaps -- a scheduled summary, then
        // nothing for an hour -- so the model was liable to be evicted between them. Reloading
        // it costs 35-119 seconds measured on this hardware, which is longer than most of the
        // calls themselves.
        //
        // -1 means never unload. It is set per request rather than relying on the host, because
        // the host's setting lives in a systemd unit that the Ollama installer rewrites on every
        // upgrade -- that is exactly how OLLAMA_HOST was silently lost. A value carried in the
        // request cannot be lost that way.
        //
        // The cost is that the model holds its memory permanently on that box. That is the right
        // trade for a dedicated inference host and the wrong one for a shared machine; if it
        // ever needs to change, this is the single place to change it.
        body.put("keep_alive", -1);

        // JSON mode: force structured JSON output when requested
        if (reqConfig.jsonMode()) {
            body.put("format", "json");
        }

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

        try (Response response = clientForRequest(reqConfig).newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errBody = response.body() != null ? response.body().string() : "";
                throw new LlmException("ollama", "HTTP " + response.code() + ": " + errBody,
                        response.code(), null);
            }

            JsonNode json = mapper.readTree(response.body().string());
            String content = json.path("message").path("content").asText("");
            // Thinking models (Ollama reports a "thinking" capability) put their reasoning in a
            // separate field and only then write the answer to content. Thinking is left ENABLED
            // deliberately — it is what makes a small local model usable on real work — but the
            // reasoning consumes the num_predict budget, so a budget that is too small ends the
            // turn mid-thought with an empty content. Reading only content made that look like a
            // successful empty reply, which downstream became "unparseable output" and a retry.
            String thinking = json.path("message").path("thinking").asText("");
            String doneReason = json.path("done_reason").asText("");
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
            if (!thinking.isBlank()) {
                log.debug("Ollama [{}]: {} thinking chars before the answer", model, thinking.length());
            }
            if (content.isBlank() && !thinking.isBlank()) {
                // Fail loudly instead of returning "" — the caller can raise the budget, whereas
                // an empty string just becomes a mystery parse failure several layers away.
                throw new LlmException("ollama",
                        "Model '" + model + "' used its whole output budget on reasoning and never "
                                + "produced an answer (" + completionTokens + " tokens generated, done_reason="
                                + doneReason + ", " + thinking.length() + " chars of thinking). Raise "
                                + "max_tokens for this call, or use a model that reasons more briefly.",
                        0, null);
            }
            if ("length".equals(doneReason)) {
                log.warn("Ollama [{}]: output truncated at the token limit ({} tokens) — the answer is "
                        + "incomplete", model, completionTokens);
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

    @Override
    public String model() { return config.getModel(); }

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
