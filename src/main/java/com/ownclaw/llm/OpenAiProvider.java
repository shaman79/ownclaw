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
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenAI REST API provider for cloud LLM inference (Mentor).
 * <p>
 * API: POST https://api.openai.com/v1/chat/completions, with the reply streamed as server-sent
 * events. No max_completion_tokens is sent, so the model may write up to its own maximum.
 * <p>
 * Reasoning models reject sampling parameters — see {@link #supportsSampling(String)}.
 */
// Not a bean, and not public: CloudGateway is the only thing that constructs this, and a
// test walks the source tree to keep it so. Every cloud call goes through the door.
class OpenAiProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiProvider.class);
    private static final MediaType JSON_TYPE = MediaType.get("application/json");
    private static final String BASE_URL = "https://api.openai.com/v1";

    /** The {@code o<n>} reasoning family: o1, o3-mini, o4-mini-2025-04-16, ... */
    private static final Pattern REASONING_FAMILY = Pattern.compile("^o\\d");

    /** The {@code gpt-<major>} generation, e.g. gpt-4o -> 4, gpt-5-mini -> 5. */
    private static final Pattern GPT_GENERATION = Pattern.compile("^gpt-(\\d+)");

    private final OwnClawConfig.Mentor config;
    private final ObjectMapper mapper;
    private final OkHttpClient httpClient;

    OpenAiProvider(OwnClawConfig ownClawConfig, ObjectMapper mapper) {
        this(ownClawConfig, mapper, new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                // The reply is streamed, so this is the longest silence allowed between two
                // chunks, not the length of the call.
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build());
    }

    /** With the HTTP client given, so a test can answer the calls itself. */
    OpenAiProvider(OwnClawConfig ownClawConfig, ObjectMapper mapper, OkHttpClient httpClient) {
        this.config = ownClawConfig.getMentor();
        this.mapper = mapper;
        this.httpClient = httpClient;
    }

    @Override
    public LlmResponse chat(List<LlmMessage> messages, LlmRequestConfig reqConfig) {
        return RateLimitBackoff.execute(() -> chatInternal(messages, reqConfig), "openai", reqConfig.progress());
    }

    private LlmResponse chatInternal(List<LlmMessage> messages, LlmRequestConfig reqConfig) {
        String apiKey = config.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException("openai", "API key not configured");
        }

        String model = reqConfig.model() != null ? reqConfig.model() : config.getModel();

        Request request = new Request.Builder()
                .url(BASE_URL + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody(messages, reqConfig, model).toString(), JSON_TYPE))
                .build();

        return StreamedCall.send(httpClient, request, "openai", reqConfig.progress(), response -> {
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful()) {
                int code = response.code();
                String error = responseBody != null ? responseBody.string() : "";
                if (code == 400 && error.contains("context_length_exceeded")) {
                    throw new OutputTruncated("openai", OutputTruncated.Limit.CONTEXT_WINDOW, null, null);
                }
                throw new LlmException("openai", "HTTP " + code + ": " + error, code, null);
            }
            if (responseBody == null) {
                throw new LlmException("openai", "HTTP " + response.code() + " with no body", 0, null);
            }
            return read(responseBody.source(), model, reqConfig.progress());
        });
    }

    /** The request body for one call. Package-private so a test can see exactly what would be sent. */
    ObjectNode requestBody(List<LlmMessage> messages, LlmRequestConfig reqConfig, String model) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("stream", true);
        // Without it the stream carries no token counts at all.
        body.putObject("stream_options").put("include_usage", true);

        // Reasoning models pick their own sampling and reject any explicit value with
        // HTTP 400 "Unsupported value: 'temperature' ... Only the default (1) is supported."
        if (supportsSampling(model)) {
            double temperature = reqConfig.temperature() != null ? reqConfig.temperature() : config.getTemperature();
            body.put("temperature", temperature);
        } else {
            log.debug("OpenAI [{}]: omitting temperature, not supported by this model", model);
        }

        // response_format and tools are mutually exclusive in practice: asking for a JSON
        // object pushes the model to write JSON into the message body instead of emitting
        // tool_calls, which is the one behaviour native tools exist to replace.
        if (reqConfig.jsonMode() && !reqConfig.hasTools()) {
            body.putObject("response_format").put("type", "json_object");
        }

        ArrayNode msgs = body.putArray("messages");
        for (LlmMessage msg : messages) {
            ObjectNode m = msgs.addObject();
            m.put("role", msg.role().apiValue());
            m.put("content", msg.content());
        }

        if (reqConfig.hasTools()) {
            ArrayNode toolsArray = body.putArray("tools");
            for (ToolSpec spec : reqConfig.tools()) {
                ObjectNode fn = toolsArray.addObject().put("type", "function").putObject("function");
                fn.put("name", spec.name());
                fn.put("description", spec.description() == null ? "" : spec.description());
                fn.set("parameters", mapper.valueToTree(spec.inputSchema()));
            }
            // One action per step, one observation recorded. Accepting two calls would mean
            // silently dropping work the model asked for.
            body.put("parallel_tool_calls", false);
        }
        return body;
    }

    /**
     * The reply, chunk by chunk, until {@code data: [DONE]}. The progress hook hears every
     * chunk, and whatever it throws leaves through here untouched, closing the stream. An
     * attempt that ends here without a reply after its counts arrived first tells the hook what
     * it is billed for ({@link LlmProgress#billed}).
     */
    private LlmResponse read(BufferedSource source, String requestedModel, LlmProgress progress)
            throws IOException {
        var events = new ServerSentEvents(source);
        var reply = new StreamedReply("openai", mapper);
        String servedModel = requestedModel;
        String finishReason = null;
        int promptTokens = 0, completionTokens = 0, cachedPromptTokens = 0;
        boolean done = false;

        try {
            ServerSentEvents.Event event;
            while ((event = events.next()) != null) {
                progress.onProgress();
                if ("[DONE]".equals(event.data().strip())) {
                    done = true;
                    break;
                }
                JsonNode chunk = reply.parse(event.data());
                if (chunk.hasNonNull("error")) throw streamError(chunk.path("error"));
                servedModel = chunk.path("model").asText(servedModel);
                for (JsonNode choice : chunk.path("choices")) {
                    JsonNode delta = choice.path("delta");
                    if (delta.path("content").isTextual()) reply.text(delta.path("content").asText());
                    // Arguments arrive as a JSON STRING in fragments, unlike Anthropic's input
                    // object; the first fragment of a call carries its id and name.
                    for (JsonNode tc : delta.path("tool_calls")) {
                        reply.call(tc.path("index").asInt(), tc.path("id").asText(null),
                                tc.path("function").path("name").asText(null),
                                tc.path("function").path("arguments").asText(null));
                    }
                    finishReason = choice.path("finish_reason").asText(finishReason);
                }
                // The last chunk before [DONE]: no choices, only the counts.
                JsonNode usage = chunk.path("usage");
                if (usage.isObject()) {
                    promptTokens = usage.path("prompt_tokens").asInt(0);
                    completionTokens = usage.path("completion_tokens").asInt(0);
                    cachedPromptTokens = usage.path("prompt_tokens_details").path("cached_tokens").asInt(0);
                }
            }
            if (!done) {
                throw new LlmException("openai",
                        "the reply stream ended before [DONE], so the reply is incomplete", 0, null);
            }
        } catch (IOException | RuntimeException noReply) {
            if (promptTokens + completionTokens > 0) {
                progress.billed(billed(servedModel, promptTokens, completionTokens, cachedPromptTokens));
            }
            throw noReply;
        }

        log.debug("OpenAI [{}]: {} prompt ({} cached) + {} completion tokens",
                servedModel, promptTokens, cachedPromptTokens, completionTokens);
        return reply.response(List.of(billed(servedModel, promptTokens, completionTokens, cachedPromptTokens)),
                finishReason, null, servedModel, null, null);
    }

    /**
     * What one attempt is billed for, from the counts OpenAI reported.
     * <p>
     * OpenAI reports cached prompt tokens INSIDE prompt_tokens and breaks them out under
     * prompt_tokens_details.cached_tokens. They were priced at the full input rate, which on a
     * long conversation is the dominant term and about ten times what it costs. The cached
     * portion is split out so ModelPricing can charge it as a cache read; the sum still equals
     * prompt_tokens, so no token is counted twice.
     */
    private static LlmResponse.Usage billed(String model, int promptTokens, int completionTokens,
                                            int cachedPromptTokens) {
        return new LlmResponse.Usage(model, Math.max(0, promptTokens - cachedPromptTokens),
                completionTokens, 0, cachedPromptTokens);
    }

    /** An error in the middle of a reply, with the status the same error has as a whole response. */
    private static LlmException streamError(JsonNode error) {
        String type = error.path("type").asText("error");
        String code = error.path("code").asText("");
        int status = type.contains("rate_limit") || code.contains("rate_limit") ? 429
                : "server_error".equals(type) ? 500
                : 0;
        return new LlmException("openai", "the reply stream reported " + type + ": "
                + error.path("message").asText(""), status, null);
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
    public boolean supportsTools() {
        return true;
    }

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public String model() { return config.getModel(); }
}
