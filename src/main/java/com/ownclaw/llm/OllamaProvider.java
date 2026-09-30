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
                // The reply is streamed, so this is the longest silence allowed between two lines.
                // Ollama's first line comes only after it has loaded the model and read the whole
                // prompt, which on this hardware takes minutes for a cold model or a long prompt.
                .readTimeout(600, TimeUnit.SECONDS)
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
     * The context settings every {@code /api/chat} request this application sends carries --
     * this provider's, the ops probe's and the /setup benchmark's.
     * <p>
     * num_ctx is the model's own context length ({@link LocalModelCheck#contextLength}), and it
     * and shift are the same in every request because a request whose num_ctx or shift differs
     * from the loaded model's makes Ollama load the model again, which takes minutes on this
     * host. truncate and shift are off: left on, Ollama silently drops the oldest messages of a
     * prompt that does not fit, and silently slides the window during a reply that fills it.
     * Off, the first is an error and the second a reply that ends with done_reason "length" --
     * both reported as the context window they are.
     */
    public static void contextSettings(ObjectNode body, int contextLength) {
        body.put("truncate", false);
        body.put("shift", false);
        ObjectNode options = body.has("options") ? (ObjectNode) body.get("options") : body.putObject("options");
        options.put("num_ctx", contextLength);
    }

    @Override
    public LlmResponse chat(List<LlmMessage> messages, LlmRequestConfig reqConfig) {
        String model = reqConfig.model() != null ? reqConfig.model() : config.getModel();
        double temperature = reqConfig.temperature() != null ? reqConfig.temperature() : config.getTemperature();
        int contextLength = localModelCheck.contextLength(model);

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("stream", true);

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
        // NOT when tools are offered. Forcing JSON output pushes the model to put the action
        // JSON in the message body instead of emitting tool_calls -- the exact behaviour native
        // tools replace -- and a local model is far more suggestible about this than a frontier
        // one, so the two settings together reliably produce the old protocol wearing the new
        // one's clothes.
        if (reqConfig.jsonMode() && !reqConfig.hasTools()) {
            body.put("format", "json");
        }

        // No num_predict, ever: the model writes until it stops or its context window is full.
        body.putObject("options").put("temperature", temperature);
        contextSettings(body, contextLength);

        if (reqConfig.hasTools()) {
            ArrayNode toolsArray = body.putArray("tools");
            for (ToolSpec spec : reqConfig.tools()) {
                ObjectNode fn = toolsArray.addObject().put("type", "function").putObject("function");
                fn.put("name", spec.name());
                fn.put("description", spec.description() == null ? "" : spec.description());
                fn.set("parameters", mapper.valueToTree(spec.inputSchema()));
            }
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
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful()) {
                String error = responseBody != null ? responseBody.string() : "";
                throw failure("HTTP " + response.code() + ": " + error, response.code(), contextLength);
            }
            if (responseBody == null) {
                throw new LlmException("ollama", "HTTP " + response.code() + " with no body", 0, null);
            }
            // The one check every reply passes before a caller sees it -- here, because local
            // calls have no gateway to apply it.
            return read(responseBody.source(), model, contextLength, reqConfig.progress())
                    .requireComplete("ollama");
        } catch (IOException e) {
            throw new LlmException("ollama", "Connection failed: " + e.getMessage(), 0, e);
        }
    }

    /**
     * The reply, line by line, until the one with {@code "done": true}. The progress hook hears
     * every line, and whatever it throws leaves through here untouched, closing the stream.
     */
    private LlmResponse read(BufferedSource source, String model, int contextLength,
                             LlmProgress progress) throws IOException {
        var reply = new StreamedReply("ollama", mapper);
        int thinkingChars = 0;
        int calls = 0;
        String line;
        while ((line = source.readUtf8Line()) != null) {
            if (line.isBlank()) continue;
            progress.onProgress();
            JsonNode chunk = reply.parse(line);
            if (chunk.hasNonNull("error")) {
                throw failure("the reply stream reported: " + chunk.path("error").asText(), 0, contextLength);
            }
            JsonNode message = chunk.path("message");
            reply.text(message.path("content").asText(""));
            // Thinking models (Ollama reports a "thinking" capability) put their reasoning in a
            // separate field before writing the answer to content. It is not part of the answer.
            thinkingChars += message.path("thinking").asText("").length();
            // Ollama sends each tool call whole, its arguments an object -- like Anthropic's
            // input and unlike OpenAI's string -- and they go through the same strict parse.
            for (JsonNode tc : message.path("tool_calls")) {
                int index = calls++;
                reply.call(index, tc.path("id").asText(null), tc.path("function").path("name").asText(null),
                        tc.path("function").path("arguments").toString());
                reply.close(index);
            }
            if (!chunk.path("done").asBoolean(false)) continue;

            int promptTokens = chunk.path("prompt_eval_count").asInt(0);
            int completionTokens = chunk.path("eval_count").asInt(0);
            long promptDurationNs = chunk.path("prompt_eval_duration").asLong(0);
            long evalDurationNs = chunk.path("eval_duration").asLong(0);
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
            if (thinkingChars > 0) {
                log.debug("Ollama [{}]: {} thinking chars before the answer", model, thinkingChars);
            }
            // No output limit was sent, so a "length" here is the context window filling up.
            return reply.response(promptTokens, completionTokens, 0, 0,
                    chunk.path("done_reason").asText(null), null, model, null, contextLength);
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
    public boolean isAvailable() {
        String url = config.getUrl().replaceAll("/+$", "") + "/api/tags";
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            return response.isSuccessful();
        } catch (IOException e) {
            return false;
        }
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
