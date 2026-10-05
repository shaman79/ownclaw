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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * - A request the model's safety classifiers decline is re-run by Anthropic, inside the same
 *   call, on the fallback model it recommends for the refusal's category
 *   ({@code "fallbacks": "default"}). Anthropic skips that for a decline while a tool call is
 *   still streaming and when the fallback model is rate limited or overloaded; when the refusal
 *   then names a model to retry on directly ({@code stop_details.recommended_model}), this
 *   provider sends the request to that model, once. A refusal that names none stands, and
 *   reaches the caller as {@link ProviderRefused}.
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
     * claude-opus-5 caller: Anthropic re-runs a declined request, inside the same call, on the
     * model it picks for the refusal's category -- a cyber-category decline on Opus 4.8 -- except
     * in the cases the class note names. Sent, comma-separated with {@link #CONTEXT_WINDOW_BETA},
     * on every request that asks for fallbacks; the retry on a model a refusal names asks for
     * none and sends only the other.
     */
    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    /**
     * Sent on every request. max_tokens here is always the model's own maximum, so input plus
     * max_tokens can exceed the context window long before the input alone does. Claude 4.5 and
     * newer accept that and stop with model_context_window_exceeded if the reply reaches the
     * window; an older model -- the yaml default is one -- fails such a request validation
     * outright unless this beta asks for the newer behaviour.
     */
    static final String CONTEXT_WINDOW_BETA = "model-context-window-exceeded-2025-08-26";

    /**
     * Tools offered partway through a conversation: a deferred tool ({@code defer_loading}) is
     * sent with every request but read by the model only from the {@code tool_addition} that
     * names it, a mid-conversation system message, so the cached prefix is never edited.
     */
    static final String TOOL_CHANGES_BETA = "mid-conversation-tool-changes-2026-07-01";

    /**
     * The refusal categories Anthropic bills even when the model declined before writing
     * anything. A decline before any output in any other category, or in none, is not billed.
     */
    private static final Set<String> BILLED_BEFORE_OUTPUT = Set.of("bio", "frontier_llm", "reasoning_extraction");

    /**
     * Matches a modern model id: {@code claude-<family>-<major>[-<minor>][-<date>]}.
     * The minor group is written so it never swallows an 8-digit date suffix
     * (claude-sonnet-4-20250514 parses as 4, not 4.20).
     */
    private static final Pattern MODEL_GENERATION =
            Pattern.compile("claude-([a-z]+)-(\\d+)(?:-(\\d{1,2})(?!\\d))?");

    /** What the Models API says about one model: its maximum output and its context window. */
    record ModelLimits(int maxOutputTokens, int contextWindow) {}

    /** One request's reply, and the model its refusal names to retry on directly, or null. */
    private record Attempt(LlmResponse reply, String retryOn) {}

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
        String model = reqConfig.model() != null ? reqConfig.model() : config.getAnthropicModel();
        LlmProgress progress = reqConfig.progress();
        Attempt first = RateLimitBackoff.execute(() -> send(messages, reqConfig, model, true),
                "anthropic", progress);
        if (first.retryOn() == null) return first.reply();

        // Anthropic declined without running its fallback and named the model to retry on
        // directly. Once, and without fallbacks, so it cannot chain: a refusal from that model is
        // the answer. The reply is the retry's, billed for both attempts.
        log.warn("Anthropic [{}] declined ({}) without running its fallback; retrying once on {}, "
                        + "the model the refusal names", first.reply().model(),
                first.reply().stopDescription(), first.retryOn());
        LlmResponse retry;
        try {
            retry = RateLimitBackoff.execute(
                    () -> send(messages, reqConfig, first.retryOn(), false), "anthropic", progress).reply();
        } catch (LlmException e) {
            // The rescue failed. The decline is still why there is no answer, and its tokens were
            // billed, so it is the refusal the caller gets.
            log.warn("Anthropic: the retry on {} failed ({}); the refusal stands", first.retryOn(),
                    e.getMessage());
            return first.reply();
        } catch (RuntimeException stopped) {
            // What the hook threw ends the call during the retry, and the refusal that would have
            // been returned goes with it: its tokens were billed, so the hook is told of them.
            first.reply().usage().forEach(progress::billed);
            throw stopped;
        }
        var usage = new ArrayList<>(first.reply().usage());
        usage.addAll(retry.usage());
        return new LlmResponse(retry.content(), retry.toolCalls(), retry.invalidToolCall(),
                retry.stopReason(), retry.stopDetail(), retry.model(), retry.maxOutputTokens(),
                retry.contextWindow(), usage);
    }

    /**
     * One request to {@code model}: with Anthropic's server-side fallbacks, or -- the retry on a
     * model a refusal named -- without them.
     */
    private Attempt send(List<LlmMessage> messages, LlmRequestConfig reqConfig, String model,
                         boolean withFallbacks) {
        String apiKey = config.getAnthropicApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException("anthropic", "API key not configured");
        }

        ModelLimits modelLimits = limits(model, apiKey, reqConfig.progress());

        ObjectNode body = requestBody(messages, reqConfig, model, modelLimits.maxOutputTokens());
        if (!withFallbacks) body.remove("fallbacks");

        Request request = new Request.Builder()
                .url(BASE_URL + "/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .header("anthropic-beta", (withFallbacks ? FALLBACK_BETA + "," : "") + CONTEXT_WINDOW_BETA
                        + (supportsToolChanges(model) && reqConfig.hasTools()
                                && reqConfig.tools().stream().anyMatch(ToolSpec::deferred)
                                ? "," + TOOL_CHANGES_BETA : ""))
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build();

        return StreamedCall.send(httpClient, request, "anthropic", reqConfig.progress(), response -> {
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful()) {
                int code = response.code();
                String error = responseBody != null ? responseBody.string() : "";
                // Too long for the model either way: a prompt of more tokens than its window, or a
                // request larger than the API takes at all (413 request_too_large) -- which a
                // prompt of text only reaches far past the window, since 32 MB of text is several
                // million tokens. Neither is worth sending again.
                if (code == 413 || code == 400 && error.contains("prompt is too long")) {
                    throw new OutputTruncated("anthropic", OutputTruncated.Limit.CONTEXT_WINDOW,
                            modelLimits.contextWindow(), null);
                }
                // Anthropic uses 429 for rate limits, 529 for overload, 401 for auth
                throw new LlmException("anthropic", "HTTP " + code + ": " + error, code, null);
            }
            if (responseBody == null) {
                throw new LlmException("anthropic", "HTTP " + response.code() + " with no body", 0, null);
            }
            return read(responseBody.source(), model, modelLimits, reqConfig.progress(), withFallbacks);
        });
    }

    /**
     * The reply, event by event. The progress hook hears every event, pings included, and
     * whatever it throws leaves through here untouched, closing the stream on its way out. An
     * attempt that ends here without a reply -- stopped, cut off, or ended by an error event --
     * first tells the hook what the stream had said it is billed for ({@link LlmProgress#billed}).
     *
     * @param withFallbacks whether the request asked for fallbacks, so a refusal naming a model
     *                      to retry on is one to retry
     */
    private Attempt read(BufferedSource source, String requestedModel, ModelLimits modelLimits,
                         LlmProgress progress, boolean withFallbacks) throws IOException {
        var events = new ServerSentEvents(source);
        var reply = new StreamedReply("anthropic", mapper);
        String servedModel = requestedModel;
        String stopReason = null;
        String stopDetail = null;
        String recommendedModel = null;
        // cacheWrite is every token written to the cache; cacheWriteHour the part of it written
        // for an hour, which the usage breaks out under cache_creation and which costs more.
        int input = 0, output = 0, cacheWrite = 0, cacheWriteHour = 0, cacheRead = 0;
        JsonNode iterations = null;
        boolean stopped = false;

        try {
            ServerSentEvents.Event event;
            while ((event = events.next()) != null) {
                progress.onProgress();
                JsonNode data = reply.parse(event.data());
                switch (data.path("type").asText("")) {
                    case "message_start" -> {
                        JsonNode message = data.path("message");
                        // The model that is answering. When the requested model declined before
                        // writing anything, this already names the fallback model -- and so it
                        // does on the turns after a decline, which Anthropic keeps sending
                        // straight to the model that answered it (sticky routing), with no
                        // fallback block to say so.
                        servedModel = message.path("model").asText(servedModel);
                        JsonNode usage = message.path("usage");
                        input = count(usage, "input_tokens", input);
                        output = count(usage, "output_tokens", output);
                        cacheWrite = count(usage, "cache_creation_input_tokens", cacheWrite);
                        cacheWriteHour = count(usage.path("cache_creation"), "ephemeral_1h_input_tokens", cacheWriteHour);
                        cacheRead = count(usage, "cache_read_input_tokens", cacheRead);
                        if (usage.path("iterations").isArray()) iterations = usage.path("iterations");
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
                        JsonNode details = delta.path("stop_details");
                        stopDetail = details.path("category").asText(stopDetail);
                        recommendedModel = details.path("recommended_model").asText(recommendedModel);
                        JsonNode usage = data.path("usage");
                        input = count(usage, "input_tokens", input);
                        output = count(usage, "output_tokens", output);
                        cacheWrite = count(usage, "cache_creation_input_tokens", cacheWrite);
                        cacheWriteHour = count(usage.path("cache_creation"), "ephemeral_1h_input_tokens", cacheWriteHour);
                        cacheRead = count(usage, "cache_read_input_tokens", cacheRead);
                        if (usage.path("iterations").isArray()) iterations = usage.path("iterations");
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
        } catch (IOException | RuntimeException noReply) {
            // The prompt the stream said was read was billed, and whatever output it had counted:
            // the call's caller would otherwise never hear of those tokens.
            if (input + output + cacheWrite + cacheRead > 0) {
                progress.billed(new LlmResponse.Usage(servedModel, input, output, cacheWrite, cacheRead,
                        cacheWriteHour));
            }
            throw noReply;
        }

        if (cacheRead > 0 || cacheWrite > 0) {
            log.info("Anthropic [{}]: {} input + {} output tokens (cache: {} created, {} of them for "
                    + "an hour, {} read)", servedModel, input, output, cacheWrite, cacheWriteHour, cacheRead);
        } else {
            log.debug("Anthropic [{}]: {} input + {} output tokens", servedModel, input, output);
        }
        // Carry the cache counters through. input_tokens excludes both of them, so dropping them
        // understates the billed input by most of the prompt on a cached conversation.
        boolean refused = "refusal".equals(stopReason);
        var billed = new ArrayList<LlmResponse.Usage>();
        if (iterations != null && !iterations.isEmpty()) {
            // Every attempt, each billed on its own: the top-level counts describe only the one
            // that produced this message. An earlier attempt declined; it is billed when it wrote
            // something first. One that declined before writing anything is billed only in some
            // categories, and the reply does not say which it declined in, so it is counted as
            // unbilled -- which is right for a cyber decline, the one Anthropic documents its
            // default fallback for.
            for (int i = 0; i < iterations.size(); i++) {
                JsonNode attempt = iterations.get(i);
                int attemptOutput = count(attempt, "output_tokens", 0);
                boolean last = i == iterations.size() - 1;
                if (last ? billed(refused, attemptOutput, stopDetail) : attemptOutput > 0) {
                    billed.add(new LlmResponse.Usage(attempt.path("model").asText(servedModel),
                            count(attempt, "input_tokens", 0), attemptOutput,
                            count(attempt, "cache_creation_input_tokens", 0),
                            count(attempt, "cache_read_input_tokens", 0),
                            count(attempt.path("cache_creation"), "ephemeral_1h_input_tokens", 0)));
                }
            }
        } else if (billed(refused, output, stopDetail)) {
            billed.add(new LlmResponse.Usage(servedModel, input, output, cacheWrite, cacheRead, cacheWriteHour));
        }
        LlmResponse response = reply.response(billed, stopReason, stopDetail, servedModel,
                modelLimits.maxOutputTokens(), modelLimits.contextWindow());
        boolean retry = withFallbacks && refused && recommendedModel != null && !recommendedModel.isBlank();
        return new Attempt(response, retry ? recommendedModel : null);
    }

    /**
     * Whether an attempt that ended this way is billed: every attempt that wrote something is,
     * and one refused before writing anything only in the categories Anthropic bills for that.
     */
    private static boolean billed(boolean refused, int outputTokens, String category) {
        return !refused || outputTokens > 0
                || (category != null && BILLED_BEFORE_OUTPUT.contains(category));
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
     * cannot be made without it, because max_tokens is required. The lookup is part of the call
     * that needs it, so its hook holds the lookup's cancel as it holds the request's.
     */
    private ModelLimits limits(String model, String apiKey, LlmProgress progress) {
        ModelLimits known = limits.get(model);
        if (known != null) return known;
        Request request = new Request.Builder()
                .url(HttpUrl.get(BASE_URL).newBuilder().addPathSegment("models").addPathSegment(model).build())
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .get()
                .build();
        ModelLimits found = StreamedCall.send(httpClient, request, "anthropic", progress, response -> {
            String text = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new LlmException("anthropic", "the Models API could not describe '" + model
                        + "': HTTP " + response.code() + ": " + text, response.code(), null);
            }
            JsonNode json;
            try {
                json = mapper.readTree(text);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new LlmException("anthropic", "the Models API's answer about '" + model
                        + "' is not JSON (" + e.getOriginalMessage() + ")", 0, e);
            }
            int maxOutput = json.path("max_tokens").asInt(0);
            int window = json.path("max_input_tokens").asInt(0);
            if (maxOutput <= 0 || window <= 0) {
                throw new LlmException("anthropic", "the Models API gave no max_tokens and "
                        + "max_input_tokens for '" + model + "', so its limits are unknown");
            }
            return new ModelLimits(maxOutput, window);
        });
        limits.put(model, found);
        return found;
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
        //
        // A task's think call is a stable prefix -- the tools, the system prompt and the task,
        // which the engine ends with its marker in the first message on every step -- then the
        // steps so far. The prefix is the same bytes for the whole task and, but for the task, for
        // the next turns of the chat, so it is cached for an hour (TTL "1h", writes at 2x the
        // input rate, reads at the usual rate): a delegation that keeps the cloud waiting for
        // twenty minutes, or the owner's next message ten minutes on, used to find it expired
        // after the default five and pay for writing all of it again. The steps after it slide
        // on the five-minute cache. The API takes the longer-lived marks before the shorter ones,
        // which this order is. A request without the marker -- code generation, a summary -- has
        // no prefix worth an hour: its marks are all five-minute ones.
        String systemPrompt = null;
        ArrayNode msgs = body.putArray("messages");
        boolean stable = false;
        // A model that takes no tool changes -- the one a refusal is retried on, say -- is sent
        // what a provider without deferred loading is: the tools offered so far, none deferred,
        // and no TOOLS message.
        List<ToolSpec> tools = !reqConfig.hasTools() ? List.of()
                : supportsToolChanges(model) ? reqConfig.tools() : ToolSpec.offered(reqConfig.tools(), messages);
        // A TOOLS message offers deferred tools of this request, each once: a name that is not
        // one -- a tool deleted since, or one offered from the start -- would be refused.
        java.util.Set<String> deferred = new java.util.HashSet<>();
        for (ToolSpec t : tools) if (t.deferred()) deferred.add(t.name());
        for (LlmMessage msg : messages) {
            if (msg.role() == LlmMessage.Role.TOOLS) {
                var added = msg.addedTools().stream().filter(deferred::remove).toList();
                if (added.isEmpty()) continue;
                ObjectNode m = msgs.addObject();
                m.put("role", "system");
                ArrayNode blocks = m.putArray("content");
                for (String name : added) {
                    ObjectNode block = blocks.addObject();
                    block.put("type", "tool_addition");
                    ObjectNode ref = block.putObject("tool");
                    ref.put("type", "tool_reference");
                    ref.put("name", name);
                }
            } else if (msg.role() == LlmMessage.Role.SYSTEM) {
                systemPrompt = (systemPrompt == null)
                        ? msg.content()
                        : systemPrompt + "\n\n" + msg.content();
            } else {
                ObjectNode m = msgs.addObject();
                m.put("role", msg.role().apiValue());
                String content = msg.content();
                // Only in the first message -- the one the engine marks -- and at the last marker:
                // the same text inside a fetched page or the chat would otherwise earn a fifth
                // cache mark, and the API refuses a request with more than four.
                int cut = msgs.size() == 1 && content != null ? content.lastIndexOf(LlmMessage.CACHE_BOUNDARY) : -1;
                if (cut > 0) {
                    // The task, then -- on the first step -- what changes on every step, as two
                    // blocks with the cache mark on the first. On every later step the task is
                    // the same bytes, so it is read from the cache instead of paid for again --
                    // as one block it was re-sent in full, and then written to the cache as well.
                    stable = true;
                    ArrayNode blocks = m.putArray("content");
                    ObjectNode task = blocks.addObject();
                    task.put("type", "text");
                    task.put("text", content.substring(0, cut));
                    task.set("cache_control", cacheControl(true));
                    String rest = content.substring(cut + LlmMessage.CACHE_BOUNDARY.length());
                    if (!rest.isBlank()) {
                        ObjectNode after = blocks.addObject();
                        after.put("type", "text");
                        after.put("text", rest);
                    }
                } else {
                    m.put("content", content);
                }
            }
        }
        if (systemPrompt != null) {
            // System prompt caching: the system prompt is the same static text on every step
            // (what changes goes at the end of the last user message), so it is one block,
            // cached whole.
            ArrayNode systemArray = body.putArray("system");
            ObjectNode sysBlock = systemArray.addObject();
            sysBlock.put("type", "text");
            sysBlock.put("text", systemPrompt);
            sysBlock.set("cache_control", cacheControl(stable));
        }

        // The sliding conversation breakpoint, from the second step on: on the message before
        // the newest, so the next step -- a few messages longer, with any tool addition -- finds
        // this step's entry a few positions back, well inside the 20 positions each breakpoint looks back over. Each step
        // pays full price only for its newest turn and the context that changes with it; when
        // this entry has expired, the next step reads the task's hour-long one and writes only
        // the steps after it. With the tools, the system prompt and the task, that is four
        // marks, the most the API takes.
        // Before the newest user turn, on a message of text: a tool addition -- after that turn,
        // or before it -- is not one.
        int newest = msgs.size() - 1;
        while (newest > 0 && !"user".equals(msgs.get(newest).path("role").asText())) newest--;
        for (int i = newest - 1; i > 0; i--) {
            if (msgs.get(i).path("content").isTextual()) {
                setMessageCacheBreakpoint(msgs, i);
                break;
            }
        }

        // Native tools.
        //
        // Placed BEFORE the messages in the cached prefix, which is why this is cheaper rather
        // than dearer: the manifest currently lives in the dynamic block attached to the newest
        // message, deliberately outside the cache breakpoints, so several thousand tokens are
        // re-billed at full rate on every step. As a tools array with cache_control on the last
        // entry it is billed once and then read at a tenth -- for an hour on a think call, with
        // the rest of the stable prefix (above).
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
        //
        // A deferred tool is sent with defer_loading: the API leaves it out of the prompt until a
        // tool addition names it (above), and it may not carry the cache mark, which goes on the
        // last tool that is not deferred.
        if (reqConfig.hasTools()) {
            ArrayNode toolsArray = body.putArray("tools");
            ObjectNode lastOffered = null;
            for (ToolSpec spec : tools) {
                ObjectNode t = toolsArray.addObject();
                t.put("name", spec.name());
                t.put("description", spec.description() == null ? "" : spec.description());
                t.set("input_schema", mapper.valueToTree(spec.inputSchema()));
                t.put("eager_input_streaming", true);
                if (spec.deferred()) t.put("defer_loading", true);
                else lastOffered = t;
            }
            if (lastOffered != null) lastOffered.set("cache_control", cacheControl(stable));
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
     * Set a five-minute cache breakpoint on a message by converting its plain-text content
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
        block.set("cache_control", cacheControl(false));
    }

    /**
     * A cache mark: {@code {"type": "ephemeral"}} lives five minutes, and with {@code "ttl": "1h"}
     * an hour. No beta header is needed for either.
     */
    private ObjectNode cacheControl(boolean hour) {
        ObjectNode mark = mapper.createObjectNode();
        mark.put("type", "ephemeral");
        if (hour) mark.put("ttl", "1h");
        return mark;
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
    /**
     * Whether a model takes deferred tools and mid-conversation tool additions
     * ({@link #TOOL_CHANGES_BETA}): Claude Opus 4.8, Opus 5 and later, Sonnet 5.5 and later,
     * Fable and Mythos 5.1 and later, as Anthropic's documentation lists them -- not Sonnet 5.
     */
    static boolean supportsToolChanges(String model) {
        if (model == null || model.isBlank()) return false;
        Matcher m = MODEL_GENERATION.matcher(model.trim().toLowerCase());
        if (!m.find()) return false;
        String family = m.group(1);
        int major = Integer.parseInt(m.group(2));
        int minor = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
        int version = major * 10 + Math.min(minor, 9);
        return switch (family) {
            case "opus" -> version >= 48;
            case "sonnet" -> version >= 55;
            case "fable", "mythos" -> version >= 51;
            default -> false;
        };
    }

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
}
