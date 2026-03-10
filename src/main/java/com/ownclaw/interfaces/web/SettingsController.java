package com.ownclaw.interfaces.web;

import com.ownclaw.agent.LlmRouter;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.config.SetupWizardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST API for managing OwnClaw settings from the web UI.
 *
 * <p>Reads/writes from system_settings table via SetupWizardService.
 * Changes are applied immediately to OwnClawConfig at runtime.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SetupWizardService setupWizard;
    private final OwnClawConfig config;
    private final LlmRouter llmRouter;

    public SettingsController(SetupWizardService setupWizard, OwnClawConfig config, LlmRouter llmRouter) {
        this.setupWizard = setupWizard;
        this.config = config;
        this.llmRouter = llmRouter;
    }

    /**
     * GET /api/settings — return all current settings (keys masked for secrets).
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getSettings() {
        var result = new HashMap<String, Object>();

        // Cloud LLM
        result.put("cloud_provider", config.getMentor().getProvider());
        result.put("openai_api_key", maskKey(config.getMentor().getApiKey()));
        result.put("openai_api_key_set", config.getMentor().getApiKey() != null && !config.getMentor().getApiKey().isBlank());
        result.put("openai_model", config.getMentor().getModel());
        result.put("anthropic_api_key", maskKey(config.getMentor().getAnthropicApiKey()));
        result.put("anthropic_api_key_set", config.getMentor().getAnthropicApiKey() != null && !config.getMentor().getAnthropicApiKey().isBlank());
        result.put("anthropic_model", config.getMentor().getAnthropicModel());

        // Local LLM
        result.put("ollama_url", config.getExecutor().getUrl());
        result.put("ollama_model", config.getExecutor().getModel());

        // Diagnostics
        var diag = setupWizard.getLastDiagnostic();
        if (diag != null) {
            result.put("ollama_reachable", diag.ollamaReachable());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * PUT /api/settings — update one or more settings.
     * Body: { "key": "value", ... }
     * Only known keys are accepted.
     */
    @PutMapping
    public ResponseEntity<Map<String, Object>> updateSettings(@RequestBody Map<String, String> updates) {
        for (var entry : updates.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (value == null) continue;

            switch (key) {
                case "cloud_provider" -> {
                    String provider = value.strip().toLowerCase();
                    if ("openai".equals(provider) || "anthropic".equals(provider)) {
                        setupWizard.saveSetting("cloud_provider", provider);
                        config.getMentor().setProvider(provider);
                    }
                }
                case "openai_api_key" -> {
                    String k = value.strip();
                    if (!k.isEmpty()) {
                        setupWizard.saveSetting("openai_api_key", k);
                        config.getMentor().setApiKey(k);
                    }
                }
                case "openai_model" -> {
                    String m = value.strip();
                    if (!m.isEmpty()) {
                        setupWizard.saveSetting("mentor_model", m);
                        config.getMentor().setModel(m);
                    }
                }
                case "anthropic_api_key" -> {
                    String k = value.strip();
                    if (!k.isEmpty()) {
                        setupWizard.saveSetting("anthropic_api_key", k);
                        config.getMentor().setAnthropicApiKey(k);
                    }
                }
                case "anthropic_model" -> {
                    String m = value.strip();
                    if (!m.isEmpty()) {
                        setupWizard.saveSetting("anthropic_model", m);
                        config.getMentor().setAnthropicModel(m);
                    }
                }
                case "ollama_url" -> {
                    String url = value.strip();
                    if (!url.isEmpty()) {
                        setupWizard.saveSetting("ollama_url", url);
                        config.getExecutor().setUrl(url);
                    }
                }
                case "ollama_model" -> {
                    String m = value.strip();
                    if (!m.isEmpty()) {
                        setupWizard.saveSetting("ollama_model", m);
                        config.getExecutor().setModel(m);
                    }
                }
                default -> { /* ignore unknown keys */ }
            }
        }

        // Re-resolve cloud provider after any changes
        llmRouter.resolveCloudProvider();

        // Re-run diagnostics
        setupWizard.runDiagnostics();

        // Return updated settings
        return getSettings();
    }

    /**
     * Mask an API key for display. Shows only first 8 and last 4 chars.
     */
    private String maskKey(String key) {
        if (key == null || key.isBlank()) return "";
        if (key.length() <= 12) return "***";
        return key.substring(0, 8) + "..." + key.substring(key.length() - 4);
    }
}
