package com.ownclaw.interfaces.web;

import com.ownclaw.agent.LlmRouter;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.users.AuthService;
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
    private final AuthService authService;
    private final com.ownclaw.llm.LocalModelCheck localModelCheck;

    public SettingsController(SetupWizardService setupWizard, OwnClawConfig config,
                              LlmRouter llmRouter, AuthService authService,
                              com.ownclaw.llm.LocalModelCheck localModelCheck) {
        this.setupWizard = setupWizard;
        this.config = config;
        this.llmRouter = llmRouter;
        this.authService = authService;
        this.localModelCheck = localModelCheck;
    }

    /**
     * GET /api/settings — return all current settings (keys masked for secrets).
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getSettings(
            @RequestAttribute("userId") String userId) {
        // Discloses the provider, the models, the Ollama URL and masked key state.
        if (!authService.isOwner(userId)) return ownerOnly();
        return currentSettings();
    }

    /** The settings body, without the access check — for callers that already made one. */
    private ResponseEntity<Map<String, Object>> currentSettings() {
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

        // Live local-tier status. The wizard's ollamaReachable above is a boot-time snapshot
        // and answers the wrong question anyway: Ollama was reachable throughout the months
        // the local tier was returning nonsense. This checks that the configured model is
        // installed AND can actually be driven, which is what was silently false.
        var local = localModelCheck.status();
        result.put("local_ok", local.ok());
        result.put("local_detail", local.detail());
        result.put("local_installed", local.installed());
        result.put("local_usable", local.usable());

        return ResponseEntity.ok(result);
    }

    /**
     * PUT /api/settings — update one or more settings.
     * Body: { "key": "value", ... }
     * Only known keys are accepted.
     */
    @PutMapping
    public ResponseEntity<Map<String, Object>> updateSettings(
            @RequestBody Map<String, String> updates,
            @RequestAttribute("userId") String userId) {
        // Writing here replaces the cloud API keys and can repoint the "local" model URL at an
        // arbitrary host, which would send every supposedly-local prompt off the machine.
        if (!authService.isOwner(userId)) return ownerOnly();
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
        return currentSettings();
    }

    /**
     * Mask an API key for display. Shows only first 8 and last 4 chars.
     */
    private String maskKey(String key) {
        if (key == null || key.isBlank()) return "";
        if (key.length() <= 12) return "***";
        return key.substring(0, 8) + "..." + key.substring(key.length() - 4);
    }

    /** Every account is otherwise equal, so anything dangerous is gated on the owner. */
    private static ResponseEntity<Map<String, Object>> ownerOnly() {
        return ResponseEntity.status(403).body(Map.of("error",
                "Only the owner may use this endpoint."));
    }
}
