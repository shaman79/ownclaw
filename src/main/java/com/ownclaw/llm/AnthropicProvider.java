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
 * Anthropic Messages API provider for cloud LLM inference.
 * <p>
 * API: POST https://api.anthropic.com/v1/messages
 * <p>
 * Claude uses a different message format than OpenAI:
 * - System prompt is a top-level field, NOT in the messages array
 * - No response_format: json_object — use prompt engineering for JSON mode
 * - Uses max_tokens instead of max_completion_tokens
 * - Returns usage.input_tokens / usage.output_tokens
 * - Newer models reject sampling parameters — see {@link #supportsSampling(String)}
 */
@Component
public class AnthropicProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicProvider.class);
    private static final MediaType JSON_TYPE = MediaType.get("application/json");
    private static final String BASE_URL = "https://api.anthropic.com/v1";
    private static final String API_VERSION = "2023-06-01";

    /**
     * Fallback output budget when the caller doesn't set one. {@code max_tokens} is a
     * required field on the Messages API, so it can't simply be omitted. It is set well
     * above the old 4096 because on the newer models this budget also has to cover
     * thinking tokens — a tight ceiling truncates the answer mid-JSON, which surfaces as
     * a parse failure rather than an obvious error. 16k still returns comfortably inside
     * the non-streaming read timeout.
     */
    private static final int DEFAULT_MAX_TOKENS = 16000;

    /**
     * Matches a modern model id: {@code claude-<family>-<major>[-<minor>][-<date>]}.
     * The minor group is written so it never swallows an 8-digit date suffix
     * (claude-sonnet-4-20250514 parses as 4, not 4.20).
     */
    private static final Pattern MODEL_GENERATION =
            Pattern.compile("claude-([a-z]+)-(\\d+)(?:-(\\d{1,2})(?!\\d))?");

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
        int maxTokens = reqConfig.maxTokens() != null ? reqConfig.maxTokens() : DEFAULT_MAX_TOKENS;

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);

        // Newer models decide sampling themselves and reject the parameter with
        // HTTP 400 "temperature is deprecated for this model."
        if (supportsSampling(model)) {
            double temperature = reqConfig.temperature() != null ? reqConfig.temperature() : config.getTemperature();
            body.put("temperature", temperature);
        } else {
            log.debug("Anthropic [{}]: omitting temperature, not supported by this model", model);
        }

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
            // System prompt caching. With multi-turn mode, the system prompt is
            // fully static (no dynamic content) — the no-marker path caches it as
            // one block. The marker path is kept for backward compatibility.
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
                // No marker — cache the entire prompt (multi-turn static prompt path)
                ObjectNode sysBlock = systemArray.addObject();
                sysBlock.put("type", "text");
                sysBlock.put("text", systemPrompt);
                sysBlock.putObject("cache_control").put("type", "ephemeral");
            }
        }

        // Sliding-window conversation cache breakpoints.
        // Two breakpoints create a sliding window for multi-turn prefix caching:
        //   msgs[size-4]: hits the cache created in the PREVIOUS step
        //   msgs[size-2]: creates a cache for the NEXT step to hit
        // Together with the system breakpoint, this uses 3 of 4 allowed breakpoints.
        // Each step pays full price only for the latest turn + dynamic context;
        // all older turns are served from cache at 10% cost.
        if (msgs.size() >= 6) {
            setMessageCacheBreakpoint(msgs, msgs.size() - 4);
        }
        if (msgs.size() >= 2) {
            setMessageCacheBreakpoint(msgs, msgs.size() - 2);
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

    /**
     * Set a cache breakpoint on a message by converting its plain-text content
     * to a content-block array with cache_control.
     */
    private void setMessageCacheBreakpoint(ArrayNode msgs, int index) {
        ObjectNode msg = (ObjectNode) msgs.get(index);
        String rawContent = msg.path("content").asText("");
        msg.remove("content");
        ArrayNode contentArray = msg.putArray("content");
        ObjectNode block = contentArray.addObject();
        block.put("type", "text");
        block.put("text", rawContent);
        block.putObject("cache_control").put("type", "ephemeral");
    }

    /**
     * Whether a model still accepts sampling parameters (temperature / top_p / top_k).
     * <p>
     * Anthropic removed them from the newer generations: sending {@code temperature}
     * to one of those models fails with HTTP 400
     * {@code "temperature is deprecated for this model."} There is no replacement
     * parameter — those models manage sampling themselves, so it is simply left out.
     * <p>
     * Removed on: every fable/mythos model, Opus 4.7 and newer, Sonnet 5 and newer.
     * Still accepted on: Opus 4.6 and older, Sonnet 4.6 and older, Haiku 4.5 and older,
     * and the legacy {@code claude-3-*} ids. The check is version-based rather than a
     * hardcoded list so that models released later default to the correct behaviour.
     */
    static boolean supportsSampling(String model) {
        if (model == null || model.isBlank()) {
            return true;
        }
        Matcher m = MODEL_GENERATION.matcher(model.trim().toLowerCase());
        if (!m.find()) {
            // Legacy ids such as claude-3-5-sonnet-20241022 put the version first.
            // Every model in that era accepts sampling parameters.
            return true;
        }
        String family = m.group(1);
        int major = Integer.parseInt(m.group(2));
        int minor = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;

        if ("fable".equals(family) || "mythos".equals(family)) {
            return false;
        }
        if ("opus".equals(family)) {
            return major < 4 || (major == 4 && minor < 7);
        }
        // sonnet, haiku, and any family introduced later: gone from the 5.x generation on.
        return major < 5;
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
