package com.ownclaw.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ownclaw.config.OwnClawConfig;
import okhttp3.*;
import okio.BufferedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anthropic Messages API provider for cloud LLM inference.
 * <p>
 * API: POST https://api.anthropic.com/v1/messages, with the reply streamed as server-sent
 * events.
 * <p>
 * Claude uses a different message format than OpenAI:
 * - System prompt is a top-level field, NOT in the messages array
 * - No response_format: json_object — use prompt engineering for JSON mode
 * - max_tokens is required. It is always the model's own maximum, which the Models API
 *   ({@code GET /v1/models/{model}}) reports together with the context window
 * - Returns usage.input_tokens / usage.output_tokens, plus the two cache counters
 * - Newer models reject sampling parameters — see {@link #supportsSampling(String)}
 * - A request the model's safety classifiers decline is re-run on the fallback model Anthropic
 *   recommends, inside the same call ({@code "fallbacks": "default"})
 */
// Not a bean, and not public: CloudGateway is the only thing that constructs this, and a
// test walks the source tree to keep it so. Every cloud call goes through the door.
class AnthropicProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicProvider.class);
    private static final MediaType JSON_TYPE = MediaType.get("application/json");
    private static final String BASE_URL = "https://api.anthropic.com/v1";
    private static final String API_VERSION = "2023-06-01";

    /**
     * The beta that {@code "fallbacks": "default"} requires. Anthropic's recommendation for every
     * claude-opus-5 caller: a declined request is handed to the model Anthropic picks for the
     * refusal's category -- a cyber-category decline to Opus 4.8 -- instead of ending in a
     * refusal. It is the only beta this provider sends.
     */
    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    /**
     * Matches a modern model id: {@code claude-<family>-<major>[-<minor>][-<date>]}.
     * The minor group is written so it never swallows an 8-digit date suffix
     * (claude-sonnet-4-20250514 parses as 4, not 4.20).
     */
    private static final Pattern MODEL_GENERATION =
            Pattern.compile("claude-([a-z]+)-(\\d+)(?:-(\\d{1,2})(?!\\d))?");

    /** What the Models API says about one model: its maximum output and its context window. */
    record ModelLimits(int maxOutputTokens, int contextWindow) {}

    private final OwnClawConfig.Mentor config;
    private final ObjectMapper mapper;
    private final OkHttpClient httpClient;

    /**
     * Each model's limits, as the Models API reported them the first time this process asked.
     * A lookup that failed is not remembered: that call fails with the reason, and the next one
     * asks again.
     */
    private final Map<String, ModelLimits> limits = new ConcurrentHashMap<>();

    AnthropicProvider(OwnClawConfig ownClawConfig, ObjectMapper mapper) {
        this(ownClawConfig, mapper, new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                // The reply is streamed, so this is the longest silence allowed between two
                // events -- Anthropic sends pings while the model thinks -- not the length of
                // the call.
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build());
    }

    /** With the HTTP client given, so a test can answer the calls itself. */
    AnthropicProvider(OwnClawConfig ownClawConfig, ObjectMapper mapper, OkHttpClient httpClient) {
        this.config = ownClawConfig.getMentor();
        this.mapper = mapper;
        this.httpClient = httpClient;
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
        ModelLimits modelLimits = limits(model, apiKey);

        ObjectNode body = requestBody(messages, reqConfig, model, modelLimits.maxOutputTokens());

        Request request = new Request.Builder()
                .url(BASE_URL + "/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .header("anthropic-beta", FALLBACK_BETA)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build();

        try (Response response = clientForRequest(reqConfig).newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful()) {
                int code = response.code();
                String error = responseBody != null ? responseBody.string() : "";
                if (code == 400 && error.contains("prompt is too long")) {
                    throw new OutputTruncated("anthropic", OutputTruncated.Limit.CONTEXT_WINDOW,
                            modelLimits.contextWindow(), null);
                }
                // Anthropic uses 429 for rate limits, 529 for overload, 401 for auth
                throw new LlmException("anthropic", "HTTP " + code + ": " + error, code, null);
            }
            if (responseBody == null) {
                throw new LlmException("anthropic", "HTTP " + response.code() + " with no body", 0, null);
            }
            return read(responseBody.source(), model, modelLimits, reqConfig.progress());
        } catch (IOException e) {
            throw new LlmException("anthropic", "Connection failed: " + e.getMessage(), 0, e);
        }
    }

    /**
     * The reply, event by event. The progress hook hears every event, pings included, and
     * whatever it throws leaves through here untouched, closing the stream on its way out.
     */
    private LlmResponse read(BufferedSource source, String requestedModel, ModelLimits modelLimits,
                             LlmProgress progress) throws IOException {
        var events = new ServerSentEvents(source);
        var reply = new StreamedReply("anthropic", mapper);
        String servedModel = requestedModel;
        String stopReason = null;
        String stopDetail = null;
        int input = 0, output = 0, cacheWrite = 0, cacheRead = 0;
        boolean stopped = false;

        ServerSentEvents.Event event;
        while ((event = events.next()) != null) {
            progress.onProgress();
            JsonNode data = reply.parse(event.data());
            switch (data.path("type").asText("")) {
                case "message_start" -> {
                    JsonNode message = data.path("message");
                    // The model that is answering. When the requested model declined before
                    // writing anything, this already names the fallback model.
                    servedModel = message.path("model").asText(servedModel);
                    JsonNode usage = message.path("usage");
                    input = count(usage, "input_tokens", input);
                    output = count(usage, "output_tokens", output);
                    cacheWrite = count(usage, "cache_creation_input_tokens", cacheWrite);
                    cacheRead = count(usage, "cache_read_input_tokens", cacheRead);
                }
                case "content_block_start" -> {
                    int index = data.path("index").asInt();
                    JsonNode block = data.path("content_block");
                    switch (block.path("type").asText("")) {
                        case "text" -> reply.text(block.path("text").asText(""));
                        case "tool_use" -> reply.call(index, block.path("id").asText(null),
                                block.path("name").asText(null), null);
                        // The requested model declined part-way and the fallback model goes on
                        // from here. Its text continues the text before it; a tool call before
                        // it was the declined model's and is not part of the reply.
                        case "fallback" -> {
                            reply.discardCalls();
                            servedModel = block.path("to").path("model").asText(servedModel);
                        }
                        // thinking, redacted_thinking and block types newer than this code are
                        // not part of the answer.
                        default -> { }
                    }
                }
                case "content_block_delta" -> {
                    JsonNode delta = data.path("delta");
                    switch (delta.path("type").asText("")) {
                        case "text_delta" -> reply.text(delta.path("text").asText(""));
                        case "input_json_delta" -> reply.call(data.path("index").asInt(), null, null,
                                delta.path("partial_json").asText(""));
                        default -> { }     // thinking_delta, signature_delta, ...
                    }
                }
                case "content_block_stop" -> reply.close(data.path("index").asInt());
                case "message_delta" -> {
                    JsonNode delta = data.path("delta");
                    stopReason = delta.path("stop_reason").asText(stopReason);
                    // Only a refusal has details, and even then they may be null.
                    stopDetail = delta.path("stop_details").path("category").asText(stopDetail);
                    JsonNode usage = data.path("usage");
                    input = count(usage, "input_tokens", input);
                    output = count(usage, "output_tokens", output);
                    cacheWrite = count(usage, "cache_creation_input_tokens", cacheWrite);
                    cacheRead = count(usage, "cache_read_input_tokens", cacheRead);
                }
                case "message_stop" -> stopped = true;
                case "error" -> throw streamError(data.path("error"));
                default -> { }             // ping, and event types newer than this code
            }
        }
        if (!stopped) {
            throw new LlmException("anthropic",
                    "the reply stream ended before message_stop, so the reply is incomplete", 0, null);
        }

        if (cacheRead > 0 || cacheWrite > 0) {
            log.info("Anthropic [{}]: {} input + {} output tokens (cache: {} created, {} read)",
                    servedModel, input, output, cacheWrite, cacheRead);
        } else {
            log.debug("Anthropic [{}]: {} input + {} output tokens", servedModel, input, output);
        }
        // Carry the cache counters through. input_tokens excludes both of them, so dropping them
        // understates the billed input by most of the prompt on a cached conversation.
        return reply.response(input, output, cacheWrite, cacheRead, stopReason, stopDetail,
                servedModel, modelLimits.maxOutputTokens(), modelLimits.contextWindow());
    }

    /** A counter from a usage object, or {@code otherwise} when the object does not carry it. */
    private static int count(JsonNode usage, String field, int otherwise) {
        JsonNode n = usage.get(field);
        return n != null && n.isNumber() ? n.asInt() : otherwise;
    }

    /**
     * An {@code error} event in the middle of a reply, with the status the same error has as a
     * whole response -- so an overload part-way through is retried like an HTTP 529.
     */
    static LlmException streamError(JsonNode error) {
        String type = error.path("type").asText("error");
        int status = switch (type) {
            case "invalid_request_error" -> 400;
            case "authentication_error" -> 401;
            case "billing_error" -> 402;
            case "permission_error" -> 403;
            case "not_found_error" -> 404;
            case "request_too_large" -> 413;
            case "rate_limit_error" -> 429;
            case "api_error" -> 500;
            case "timeout_error" -> 504;
            case "overloaded_error" -> 529;
            default -> 0;
        };
        return new LlmException("anthropic", "the reply stream reported " + type + ": "
                + error.path("message").asText(""), status, null);
    }

    /**
     * The model's maximum output and context window, from {@code GET /v1/models/{model}} --
     * asked once per model, and again after a lookup that failed. There is no table of models
     * here to fall back on: a limit is either the one Anthropic states or unknown, and a call
     * cannot be made without it, because max_tokens is required.
     */
    private ModelLimits limits(String model, String apiKey) {
        ModelLimits known = limits.get(model);
        if (known != null) return known;
        Request request = new Request.Builder()
                .url(HttpUrl.get(BASE_URL).newBuilder().addPathSegment("models").addPathSegment(model).build())
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String text = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new LlmException("anthropic", "the Models API could not describe '" + model
                        + "': HTTP " + response.code() + ": " + text, response.code(), null);
            }
            JsonNode json = mapper.readTree(text);
            int maxOutput = json.path("max_tokens").asInt(0);
            int window = json.path("max_input_tokens").asInt(0);
            if (maxOutput <= 0 || window <= 0) {
                throw new LlmException("anthropic", "the Models API gave no max_tokens and "
                        + "max_input_tokens for '" + model + "', so its limits are unknown");
            }
            ModelLimits found = new ModelLimits(maxOutput, window);
            limits.put(model, found);
            return found;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new LlmException("anthropic", "the Models API's answer about '" + model
                    + "' is not JSON (" + e.getOriginalMessage() + ")", 0, e);
        } catch (IOException e) {
            throw new LlmException("anthropic", "Connection failed asking the Models API about '"
                    + model + "': " + e.getMessage(), 0, e);
        }
    }

    /**
     * The request body for one call: system blocks, messages with their cache breakpoints, and
     * tools. Package-private so a test can see exactly what would be sent.
     *
     * @param maxOutputTokens the model's own maximum output, from the Models API
     */
    ObjectNode requestBody(List<LlmMessage> messages, LlmRequestConfig reqConfig, String model,
                           int maxOutputTokens) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxOutputTokens);
        body.put("stream", true);
        body.put("fallbacks", "default");

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
        boolean firstCall = messages.stream().filter(m -> m.role() != LlmMessage.Role.SYSTEM).count() == 1;
        for (LlmMessage msg : messages) {
            if (msg.role() == LlmMessage.Role.SYSTEM) {
                systemPrompt = (systemPrompt == null)
                        ? msg.content()
                        : systemPrompt + "\n\n" + msg.content();
            } else {
                ObjectNode m = msgs.addObject();
                m.put("role", msg.role().apiValue());
                String content = msg.content();
                // Only on a task's first call -- the one message the engine marks -- and at the
                // last marker: the same text inside a fetched page or the chat would otherwise earn
                // a fifth cache mark, and the API refuses a request with more than four.
                int cut = firstCall && content != null ? content.lastIndexOf(LlmMessage.CACHE_BOUNDARY) : -1;
                if (cut > 0 && !content.substring(cut + LlmMessage.CACHE_BOUNDARY.length()).isBlank()) {
                    // The task, then what changes on every step, as two blocks with the cache
                    // mark on the first. On the next step the task is the whole first message,
                    // byte for byte, so it is read from the cache instead of paid for again --
                    // as one block it was re-sent in full, and then written to the cache as well.
                    ArrayNode blocks = m.putArray("content");
                    ObjectNode stable = blocks.addObject();
                    stable.put("type", "text");
                    stable.put("text", content.substring(0, cut));
                    stable.putObject("cache_control").put("type", "ephemeral");
                    ObjectNode rest = blocks.addObject();
                    rest.put("type", "text");
                    rest.put("text", content.substring(cut + LlmMessage.CACHE_BOUNDARY.length()));
                } else {
                    m.put("content", content);
                }
            }
        }
        if (systemPrompt != null) {
            // System prompt caching. With multi-turn mode, the system prompt is
            // fully static (no dynamic content) — the no-marker path caches it as
            // one block. The marker path is kept for backward compatibility.
            ArrayNode systemArray = body.putArray("system");
            String marker = LlmMessage.CACHE_BOUNDARY;
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

        // Native tools.
        //
        // Placed BEFORE the messages in the cached prefix, which is why this is cheaper rather
        // than dearer: the manifest currently lives in the dynamic block attached to the newest
        // message, deliberately outside the cache breakpoints, so several thousand tokens are
        // re-billed at full rate on every step. As a tools array with cache_control on the last
        // entry it is billed once and then read at a tenth.
        //
        // eager_input_streaming: the reply is streamed, and without it Anthropic holds each tool
        // input back until the whole of it is generated -- a skill's full source, say, arriving
        // as one burst after minutes of silence on the stream. With it the input streams as it
        // is written, unchecked by Anthropic, and StreamedReply parses it strictly when the
        // block ends.
        //
        // disable_parallel_tool_use: the loop executes exactly one action per step and records
        // one observation. Accepting two calls would mean either dropping one -- silently losing
        // work the model asked for -- or restructuring the trajectory. That is a later stage,
        // not a side effect of this one.
        if (reqConfig.hasTools()) {
            ArrayNode toolsArray = body.putArray("tools");
            for (ToolSpec spec : reqConfig.tools()) {
                ObjectNode t = toolsArray.addObject();
                t.put("name", spec.name());
                t.put("description", spec.description() == null ? "" : spec.description());
                t.set("input_schema", mapper.valueToTree(spec.inputSchema()));
                t.put("eager_input_streaming", true);
            }
            if (toolsArray.size() > 0) {
                ((ObjectNode) toolsArray.get(toolsArray.size() - 1))
                        .putObject("cache_control").put("type", "ephemeral");
            }
            ObjectNode choice = body.putObject("tool_choice");
            choice.put("type", "auto");
            choice.put("disable_parallel_tool_use", true);
        }

        // Claude doesn't have a response_format: json_object option.
        // JSON mode is enforced via prompt engineering (ThinkingEngine already says
        // "respond with valid JSON"). Assistant prefill is NOT used because some
        // Claude models reject it with HTTP 400.
        return body;
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
        // Whether the key is set. It is checked on first use rather than here: a request just
        // to test it would be spent on every availability check.
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public boolean supportsTools() {
        return true;
    }

    public String name() {
        return "anthropic";
    }

    @Override
    public String model() { return config.getAnthropicModel(); }

    private OkHttpClient clientForRequest(LlmRequestConfig reqConfig) {
        if (reqConfig.readTimeoutSec() != null && reqConfig.readTimeoutSec() > 0) {
            return httpClient.newBuilder()
                    .readTimeout(reqConfig.readTimeoutSec(), TimeUnit.SECONDS)
                    .build();
        }
        return httpClient;
    }
}
