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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Ollama REST API provider for local LLM inference (Executor + SkillRunner).
 * <p>
 * API: POST {url}/api/chat with {"model", "messages", "stream": true, "options": {"temperature",
 * "num_ctx"}, "truncate": false, "shift": false}. The reply streams back as one JSON object per
 * line, the last one with {@code "done": true} and the counters.
 */
@Component
public class OllamaProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaProvider.class);
    private static final MediaType JSON = MediaType.get("application/json");

    private final OwnClawConfig.Executor config;
    private final ObjectMapper mapper;
    private final OkHttpClient httpClient;
    private final LocalModelCheck localModelCheck;

    @Autowired
    public OllamaProvider(OwnClawConfig ownClawConfig, ObjectMapper mapper,
                          @org.springframework.context.annotation.Lazy LocalModelCheck localModelCheck) {
        this(ownClawConfig, mapper, localModelCheck, new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                // The reply is streamed, so this bounds the silence between two lines, and the
                // longest silence comes before the first: Ollama writes nothing, not even its
                // headers, until it has loaded the model and read the whole prompt. Measured on
                // the production host on 2026-09-30, a cold load takes about 3 minutes and the
                // prompt is read at about 100 tokens a second with the model's 262,144-token
                // window, so a prompt that fills the window is some 47 minutes of silence. An hour
                // covers that; only a server that has stopped answering is silent for longer.
                // A caller need not wait it out: the call's cancel is handed to its progress
                // hook (LlmProgress#calling), so a Stop ends it at once. A task's stall watchdog
                // does not count the silence: this timeout is what bounds it
                // (AgentContext#msSinceLastProgress).
                .readTimeout(60, TimeUnit.MINUTES)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build());
    }

    /** With the HTTP client given, so a test can answer the calls itself. */
    OllamaProvider(OwnClawConfig ownClawConfig, ObjectMapper mapper, LocalModelCheck localModelCheck,
                   OkHttpClient httpClient) {
        this.localModelCheck = localModelCheck;
        this.config = ownClawConfig.getExecutor();
        this.mapper = mapper;
        this.httpClient = httpClient;
    }

    /**
     * An Ollama server's URL as the API is addressed from it: surrounding whitespace, trailing
     * slashes and an OpenAI-style "/v1" suffix dropped, because the native API lives at the root.
     * The one rule for it -- this provider, the startup check, the ops probe and the setup wizard
     * all address Ollama through here, so a URL that works for one of them works for all of them.
     */
    public static String baseUrl(String url) {
        String base = url == null ? "" : url.strip().replaceAll("/+$", "");
        return base.endsWith("/v1") ? base.substring(0, base.length() - 3).replaceAll("/+$", "") : base;
    }

    /** The URL of the Ollama endpoint {@code path} ("/api/chat", "/api/show", ...) at {@code url}. */
    public static String endpoint(String url, String path) {
        return baseUrl(url) + path;
    }

    /**
     * One {@code POST /api/chat}, streamed and read to its last line. Every /api/chat this
     * application sends goes through here -- this provider's, the ops probe's and the /setup
     * speed sample's -- so every one streams, carries the same context settings, and is read and
     * reported the same way.
     * <p>
     * num_ctx is the model's own context length ({@link LocalModelCheck#contextLength}), and it
     * and shift are the same in every request because a request whose num_ctx or shift differs
     * from the loaded model's makes Ollama load the model again, which takes minutes on this
     * host. truncate and shift are off: left on, Ollama silently drops the oldest messages of a
     * prompt that does not fit, and silently slides the window during a reply that fills it.
     * Off, the first is an error and the second a reply that ends with done_reason "length" --
     * both reported as the context window they are. Nothing here sets num_predict: the model
     * writes until it stops or its window is full.
     * <p>
     * The progress hook hears every line, and what each line carried ({@link LlmProgress#received}),
     * and whatever it throws leaves through here untouched, closing the stream.
     *
     * @param http          the client, whose read timeout bounds the silence between two lines
     * @param body          the request; its stream, truncate, shift and num_ctx are set here
     * @param contextLength the model's own context window
     * @return the last line ({@code "done": true}), with its {@code message} holding what every
     *         line carried: all of the content, all of the thinking and every tool call
     * @throws OutputTruncated when Ollama says the prompt is longer than the window
     * @throws LlmException    for any other error Ollama reports, a line that is not JSON, a
     *                         connection that fails, or a stream that ends before its last line
     */
    public static JsonNode streamChat(OkHttpClient http, String baseUrl, ObjectNode body,
                                      int contextLength, LlmProgress progress, ObjectMapper mapper) {
        body.put("stream", true);
        body.put("truncate", false);
        body.put("shift", false);
        ObjectNode options = body.has("options") ? (ObjectNode) body.get("options") : body.putObject("options");
        options.put("num_ctx", contextLength);

        Request request = new Request.Builder()
                .url(endpoint(baseUrl, "/api/chat"))
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        return StreamedCall.send(http, request, "ollama", progress, response -> {
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful()) {
                String error = responseBody != null ? responseBody.string() : "";
                throw failure("HTTP " + response.code() + ": " + error, response.code(), contextLength);
            }
            if (responseBody == null) {
                throw new LlmException("ollama", "HTTP " + response.code() + " with no body", 0, null);
            }
            return read(responseBody.source(), contextLength, progress, mapper);
        });
    }

    /** The reply, line by line, until the one with {@code "done": true}. */
    private static JsonNode read(BufferedSource source, int contextLength, LlmProgress progress,
                                 ObjectMapper mapper) throws IOException {
        var content = new StringBuilder();
        var thinking = new StringBuilder();
        ArrayNode toolCalls = mapper.createArrayNode();
        String line;
        while ((line = source.readUtf8Line()) != null) {
            if (line.isBlank()) continue;
            progress.onProgress();
            JsonNode chunk = StreamedReply.parse("ollama", mapper, line);
            if (chunk.hasNonNull("error")) {
                throw failure("the reply stream reported: " + chunk.path("error").asText(), 0, contextLength);
            }
            JsonNode message = chunk.path("message");
            // Thinking models (Ollama reports a "thinking" capability) put their reasoning in a
            // separate field before writing the answer to content. It is not part of the answer.
            String reasoning = message.path("thinking").asText("");
            String said = message.path("content").asText("");
            thinking.append(reasoning);
            content.append(said);
            if (!reasoning.isEmpty()) progress.received(LlmProgress.Part.REASONING, reasoning);
            if (!said.isEmpty()) progress.received(LlmProgress.Part.ANSWER, said);
            for (JsonNode call : message.path("tool_calls")) {
                toolCalls.add(call);
                // Ollama sends each call whole: its name, then all of its arguments at once.
                progress.received(LlmProgress.Part.CALL, call.path("function").path("name").asText(""));
                progress.received(LlmProgress.Part.ARGUMENTS, call.path("function").path("arguments").toString());
            }
            if (!chunk.path("done").asBoolean(false)) continue;

            ObjectNode last = chunk.deepCopy();
            ObjectNode whole = last.putObject("message");
            whole.put("role", "assistant");
            whole.put("content", content.toString());
            whole.put("thinking", thinking.toString());
            whole.set("tool_calls", toolCalls);
            return last;
        }
        throw new LlmException("ollama",
                "the reply stream ended before its last line (\"done\": true), so the reply is incomplete", 0, null);
    }

    /**
     * An error Ollama reported. A prompt longer than num_ctx -- which truncate:false makes an
     * error instead of silently dropped messages -- is reported as the context window it is.
     */
    private static LlmException failure(String message, int status, int contextLength) {
        String m = message.toLowerCase(Locale.ROOT);
        if (m.contains("exceed_context_size") || m.contains("exceeds the available context size")
                || m.contains("exceeds the context length")) {
            return new OutputTruncated("ollama", OutputTruncated.Limit.CONTEXT_WINDOW, contextLength, null);
        }
        return new LlmException("ollama", message, status, null);
    }

    @Override
    public LlmResponse chat(List<LlmMessage> messages, LlmRequestConfig reqConfig) {
        String model = reqConfig.model() != null ? reqConfig.model() : config.getModel();
        double temperature = reqConfig.temperature() != null ? reqConfig.temperature() : config.getTemperature();
        int contextLength = localModelCheck.contextLength(model);

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);

        // Keep the model resident between calls.
        //
        // Nothing here set keep_alive, so every request inherited whatever the server default
        // happened to be. Ollama's own default is five minutes, and this deployment makes
        // local calls in bursts separated by much longer gaps -- a scheduled run's delegations,
        // then nothing for an hour -- so the model was liable to be evicted between them. Reloading
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
        // NOT when tools are offered. Forcing JSON output pushes the model to put the action
        // JSON in the message body instead of emitting tool_calls -- the exact behaviour native
        // tools replace -- and a local model is far more suggestible about this than a frontier
        // one, so the two settings together reliably produce the old protocol wearing the new
        // one's clothes.
        if (reqConfig.jsonMode() && !reqConfig.hasTools()) {
            body.put("format", "json");
        }

        body.putObject("options").put("temperature", temperature);

        // Only ever "false": a model without a thinking mode takes no "think": true, and leaving it
        // out keeps the model's own default (Qwen3.6 reasons first). Asked for by the call, or by
        // the owner's thinking effort at low.
        if (reqConfig.withoutThinking() || "low".equals(reqConfig.effort())) body.put("think", false);

        if (reqConfig.hasTools()) {
            ArrayNode toolsArray = body.putArray("tools");
            for (ToolSpec spec : ToolSpec.offered(reqConfig.tools(), messages)) {
                ObjectNode fn = toolsArray.addObject().put("type", "function").putObject("function");
                fn.put("name", spec.name());
                fn.put("description", spec.description() == null ? "" : spec.description());
                fn.set("parameters", mapper.valueToTree(spec.inputSchema()));
            }
        }

        ArrayNode msgs = body.putArray("messages");
        for (LlmMessage msg : messages) {
            if (msg.role() == LlmMessage.Role.TOOLS) continue;   // its tools join the array
            ObjectNode m = msgs.addObject();
            m.put("role", msg.role().apiValue());
            m.put("content", msg.content());
        }

        JsonNode last = streamChat(httpClient, config.getUrl(), body, contextLength, reqConfig.progress(), mapper);
        // The one check every reply passes before a caller sees it -- here, because local calls
        // have no gateway to apply it.
        return response(last, model, contextLength).requireComplete("ollama");
    }

    /** The reply {@link #streamChat} read, as the response every provider gives. */
    private LlmResponse response(JsonNode last, String model, int contextLength) {
        var reply = new StreamedReply("ollama", mapper);
        JsonNode message = last.path("message");
        reply.text(message.path("content").asText(""));
        // Ollama sends each tool call whole, its arguments an object -- like Anthropic's input
        // and unlike OpenAI's string -- and they go through the same strict parse.
        int index = 0;
        for (JsonNode tc : message.path("tool_calls")) {
            reply.call(index, tc.path("id").asText(null), tc.path("function").path("name").asText(null),
                    tc.path("function").path("arguments").toString());
            reply.close(index++);
        }

        int promptTokens = last.path("prompt_eval_count").asInt(0);
        int completionTokens = last.path("eval_count").asInt(0);
        long promptDurationNs = last.path("prompt_eval_duration").asLong(0);
        long evalDurationNs = last.path("eval_duration").asLong(0);
        if (completionTokens > 0 && evalDurationNs > 0) {
            double tps = completionTokens / (evalDurationNs / 1_000_000_000.0);
            if (promptTokens > 0 && promptDurationNs > 0) {
                double ptps = promptTokens / (promptDurationNs / 1_000_000_000.0);
                log.debug("Ollama [{}]: {} prompt ({} tok/s) + {} completion ({} tok/s)",
                        model, promptTokens, String.format(Locale.US, "%.1f", ptps),
                        completionTokens, String.format(Locale.US, "%.1f", tps));
            } else {
                log.debug("Ollama [{}]: {} prompt + {} completion ({} tok/s)",
                        model, promptTokens, completionTokens, String.format(Locale.US, "%.1f", tps));
            }
        } else {
            log.debug("Ollama [{}]: {} prompt + {} completion tokens", model, promptTokens, completionTokens);
        }
        int thinkingChars = message.path("thinking").asText("").length();
        if (thinkingChars > 0) {
            log.debug("Ollama [{}]: {} thinking chars before the answer", model, thinkingChars);
        }
        // No output limit was sent, so a "length" here is the context window filling up.
        return reply.response(List.of(new LlmResponse.Usage(model, promptTokens, completionTokens, 0, 0)),
                last.path("done_reason").asText(null), null, model, null, contextLength);
    }

    /**
     * Whether the server answers, asked by the startup check ({@link LocalModelCheck#reachable})
     * rather than with this provider's client. That client waits up to an hour for a reply's
     * first line, which a streamed chat needs and a probe does not: no hook holds the probe's
     * cancel, and a delegation and every task with a file ask it first, on the task's thread --
     * so a server that accepts the connection and never answers costs them the check's seconds,
     * not that hour.
     */
    @Override
    public boolean isAvailable() {
        return localModelCheck.reachable();
    }

    /**
     * Per MODEL, not per provider: Ollama serves whatever is loaded, and tool support varies
     * between them. The answer comes from the startup check, which already reads /api/show and
     * reflects any model substitution, rather than a network probe on the hot path.
     */
    @Override
    public boolean supportsTools() {
        return localModelCheck != null && localModelCheck.toolsCapable();
    }


    @Override
    public String name() {
        return "ollama";
    }

    @Override
    public String model() { return config.getModel(); }
}
