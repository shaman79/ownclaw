package com.ownclaw.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Checks at startup that the configured local model can actually be driven, and says plainly
 * what is wrong when it cannot.
 * <p>
 * Two failures were silent before this existed, and both were live on this deployment:
 * <ul>
 *   <li><b>The model is not installed.</b> Every local call returns
 *       {@code {"error":"model '...' not found"}}, which the callers swallow as an ordinary
 *       failure. The effect is a local tier that never works and never says so.</li>
 *   <li><b>The model has no chat template.</b> Ollama reports
 *       {@code capabilities: ["completion"]} and a template of {@code {{ .Prompt }}}. The chat
 *       endpoint then cannot render the messages array, so the system prompt and the role
 *       structure are discarded — {@code prompt_eval_count} comes back far smaller than the
 *       prompt and the model answers something unrelated. Delegation, conversation
 *       compression, tool pre-selection and scheduled summaries all return nonsense.</li>
 * </ul>
 * Runs on a daemon thread so a cold or unreachable Ollama never delays startup. The same facts
 * are available on demand, with a live chat round-trip, from {@code GET /api/ops/ollama}.
 */
@Component
public class LocalModelCheck {

    private static final Logger log = LoggerFactory.getLogger(LocalModelCheck.class);
    private static final MediaType JSON = MediaType.get("application/json");

    private final OwnClawConfig config;
    private final ObjectMapper mapper;
    private final OkHttpClient http;

    public LocalModelCheck(OwnClawConfig config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();
    }

    @jakarta.annotation.PostConstruct
    public void scheduleCheck() {
        Thread t = new Thread(this::check, "local-model-check");
        t.setDaemon(true);
        t.start();
    }

    private void check() {
        String url = config.getExecutor().getUrl();
        String model = config.getExecutor().getModel();
        if (url == null || url.isBlank() || model == null || model.isBlank()) {
            log.warn("Local model check skipped: executor url or model is not configured");
            return;
        }

        List<String> installed;
        try {
            installed = installedModels(url);
        } catch (Exception e) {
            log.warn("Local tier unavailable: {} is not reachable ({}). Delegation, conversation "
                            + "compression and scheduled summaries will fail until it is.",
                    url, e.getMessage());
            return;
        }

        if (!installed.contains(model)) {
            log.error("LOCAL TIER BROKEN: model '{}' is not installed on {}. Every local call will "
                            + "fail with \"model not found\". Installed: {}. Fix with `ollama pull <model>` "
                            + "on that host, or point ownclaw.executor.model (OWNCLAW_EXECUTOR_MODEL) at one "
                            + "of the models listed.",
                    model, url, installed.isEmpty() ? "(none)" : installed);
            return;
        }

        try {
            JsonNode show = show(url, model);
            var capabilities = new ArrayList<String>();
            for (JsonNode c : show.path("capabilities")) {
                capabilities.add(c.asText());
            }
            String template = show.path("template").asText("").trim();
            boolean chatUsable = chatUsable(template, capabilities);

            if (!chatUsable) {
                log.error("LOCAL TIER BROKEN: model '{}' cannot be used through /api/chat. "
                                + "capabilities={}, template is the bare \"{{ .Prompt }}\" placeholder and "
                                + "Ollama has no built-in renderer for this architecture, so it cannot "
                                + "render the message list: the system prompt and the roles are discarded "
                                + "(prompt_eval_count comes back near 1) and delegation, conversation "
                                + "compression, tool pre-selection and scheduled summaries all receive "
                                + "unrelated text. The weights are almost certainly fine — the GGUF simply "
                                + "ships without a chat_template, which is common for community HuggingFace "
                                + "GGUF uploads. Either point OWNCLAW_EXECUTOR_MODEL at a model whose "
                                + "template renders messages, or re-create this one with a Modelfile that "
                                + "supplies the right template for its tokeniser. Confirm either way with "
                                + "GET /api/ops/ollama — promptEvalCount must match the prompt size.",
                        model, capabilities);
                return;
            }

            if (capabilities.contains("thinking")) {
                log.info("Local model '{}' ready on {} (capabilities={}) — a thinking model, so its "
                        + "reasoning shares the output budget with the answer; local calls need enough "
                        + "max_tokens for both.", model, url, capabilities);
            } else {
                log.info("Local model '{}' ready on {} (capabilities={})", model, url, capabilities);
            }
        } catch (Exception e) {
            log.warn("Could not inspect local model '{}' on {}: {}", model, url, e.getMessage());
        }
    }

    /**
     * A live status probe for the local tier, cheap enough to run on a settings page load.
     *
     * @param ok     the configured model is installed AND can actually be driven
     * @param detail one line explaining why, for display
     */
    public record LocalStatus(boolean ok, String model, boolean reachable,
                              boolean installed, boolean usable, String detail) {}

    /**
     * Ask the local tier whether it is genuinely usable right now.
     * <p>
     * Written because the Settings page rendered a hardcoded green dot next to Ollama, so the
     * one screen reporting local health said it was fine throughout the months it was broken.
     * The obvious fix — binding that dot to the setup wizard's {@code ollamaReachable} — would
     * not have helped: that is a boot-time snapshot, and Ollama <em>was</em> reachable the whole
     * time. The server answered, the model was installed, and every call still returned
     * nonsense, because the server could not render a chat template for that architecture.
     * Reachability was never the question.
     * <p>
     * So this checks what actually matters: is the configured model installed, and can it be
     * driven through {@code /api/chat}. Both come from {@code /api/tags} and {@code /api/show},
     * which cost milliseconds — no inference, so no 60-133 second round trip on a page load.
     */
    public LocalStatus status() {
        String url = config.getExecutor().getUrl();
        String model = config.getExecutor().getModel();
        if (url == null || url.isBlank() || model == null || model.isBlank()) {
            return new LocalStatus(false, model, false, false, false,
                    "Local model or URL is not configured.");
        }
        List<String> installed;
        try {
            installed = installedModels(url);
        } catch (Exception e) {
            return new LocalStatus(false, model, false, false, false,
                    "Not reachable at " + url + " (" + e.getMessage() + ")");
        }
        if (!installed.contains(model)) {
            return new LocalStatus(false, model, true, false, false,
                    "Reachable, but '" + model + "' is not installed. Installed: "
                            + (installed.isEmpty() ? "(none)" : String.join(", ", installed)));
        }
        try {
            JsonNode show = show(url, model);
            var capabilities = new ArrayList<String>();
            for (JsonNode c : show.path("capabilities")) capabilities.add(c.asText());
            String template = show.path("template").asText("").trim();
            if (!chatUsable(template, capabilities)) {
                return new LocalStatus(false, model, true, true, false,
                        "Installed but NOT usable through /api/chat — no renderer for this "
                        + "architecture, so prompts are discarded and answers are unrelated. "
                        + "capabilities=" + capabilities + ". Upgrading Ollama often fixes this.");
            }
            return new LocalStatus(true, model, true, true, true,
                    "Ready (capabilities=" + capabilities + ")");
        } catch (Exception e) {
            return new LocalStatus(false, model, true, true, false,
                    "Installed, but could not be inspected: " + e.getMessage());
        }
    }

    /**
     * Whether this model can be driven through {@code /api/chat}.
     * <p>
     * Ollama has no "chat" capability — the real values are completion, tools, insert, vision,
     * embedding and thinking — so asking for one was a bug that flagged every model as broken.
     * The chat path works when something renders the message list. Three things can:
     * <ol>
     *   <li><b>A Go template</b> that walks {@code .Messages} or inserts {@code .System}.</li>
     *   <li><b>A Jinja chat template</b>, which Ollama 0.34 and later read straight from the
     *       GGUF's {@code tokenizer.chat_template} metadata. Jinja statement syntax
     *       ({@code {%- if ... %}}) is the giveaway; the bare {@code {{ .Prompt }}} fallback
     *       contains none of it.</li>
     *   <li><b>A built-in renderer</b> for the architecture. Ollama does not expose which
     *       architectures have one, but a model left with the bare placeholder that still
     *       advertises tools or thinking must have it, because neither capability is reachable
     *       without structured messages.</li>
     * </ol>
     * The Jinja case is not hypothetical and is the reason this method is not just a capability
     * check. On this deployment, {@code qwen35moe} under Ollama 0.18.3 reported
     * {@code capabilities: [completion]} with a {@code {{ .Prompt }}} template and received one
     * token of a fifty-token prompt — the GGUF's Jinja template was there all along, but 0.18.3
     * could not parse Jinja and silently fell back. Upgrading the server to 0.34.2 turned the
     * same unmodified model into {@code [tools, thinking, completion]} with a 7.7 kB Jinja
     * template. So a model that looks unusable is often a server too old to render it, and the
     * fix is the Ollama version rather than the model.
     */
    public static boolean chatUsable(String template, List<String> capabilities) {
        String t = template == null ? "" : template.trim();
        if (t.contains(".Messages") || t.contains(".System")) {
            return true;    // Go template that renders a conversation itself
        }
        if (t.contains("{%")
                && (t.contains("messages") || t.contains("im_start")
                    || t.contains("add_generation_prompt"))) {
            return true;    // Jinja chat template from the GGUF metadata
        }
        // A bare placeholder plus a richer capability set means a built-in renderer is in play.
        return capabilities.contains("tools") || capabilities.contains("thinking");
    }

    private List<String> installedModels(String url) throws Exception {
        Request req = new Request.Builder().url(url + "/api/tags").get().build();
        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                throw new IllegalStateException("HTTP " + resp.code());
            }
            var names = new ArrayList<String>();
            for (JsonNode n : mapper.readTree(body).path("models")) {
                names.add(n.path("name").asText());
            }
            return names;
        }
    }

    private JsonNode show(String url, String model) throws Exception {
        RequestBody body = RequestBody.create(
                mapper.writeValueAsString(java.util.Map.of("model", model)), JSON);
        Request req = new Request.Builder().url(url + "/api/show").post(body).build();
        try (Response resp = http.newCall(req).execute()) {
            String text = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                throw new IllegalStateException("HTTP " + resp.code() + ": " + text);
            }
            return mapper.readTree(text);
        }
    }
}
