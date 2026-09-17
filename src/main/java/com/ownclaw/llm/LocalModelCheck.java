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
            boolean chat = capabilities.contains("chat");
            boolean templateUnusable = template.isEmpty() || template.equals("{{ .Prompt }}");

            if (!chat || templateUnusable) {
                log.error("LOCAL TIER BROKEN: model '{}' cannot be used through /api/chat. "
                                + "capabilities={}, template={} chars{}. Without a chat template Ollama "
                                + "discards the system prompt and the message roles, so delegation, "
                                + "conversation compression, tool pre-selection and scheduled summaries "
                                + "return unrelated text. Pull a chat-capable model, or wrap this one in a "
                                + "Modelfile that supplies the proper template. Verify with "
                                + "GET /api/ops/ollama — promptEvalCount should match the prompt size.",
                        model, capabilities, template.length(),
                        template.equals("{{ .Prompt }}") ? " (the bare \"{{ .Prompt }}\" placeholder)" : "");
                return;
            }

            log.info("Local model '{}' ready on {} (capabilities={})", model, url, capabilities);
        } catch (Exception e) {
            log.warn("Could not inspect local model '{}' on {}: {}", model, url, e.getMessage());
        }
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
