package com.ownclaw.agent;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.llm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * The ThinkingEngine is the core reasoning component of the agent.
 * It assembles an LLM prompt from the current agent context, available tools,
 * and execution trajectory, then parses the LLM's response into an {@link AgentAction}.
 *
 * The engine is stateless — all state is carried in the {@link AgentContext}.
 */
@Component
public class ThinkingEngine {

    private static final Logger log = LoggerFactory.getLogger(ThinkingEngine.class);

    /**
     * Marker inserted into system prompts to separate the static (cacheable) prefix
     * from the dynamic suffix (datetime, tools, user prefs). AnthropicProvider splits
     * on this marker to create two system content blocks — only the static prefix gets
     * cache_control, so the Anthropic prompt cache actually hits across requests.
     */
    static final String CACHE_BOUNDARY_MARKER = "\n<!-- CACHE_BOUNDARY -->\n";

    // Lenient mapper: tolerates common LLM JSON quirks.
    // - ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER: \' and other non-standard escapes
    // - ALLOW_UNQUOTED_FIELD_NAMES: {tool: "x"} instead of {"tool": "x"}
    // - ALLOW_SINGLE_QUOTES: {'tool': 'x'} instead of {"tool": "x"}
    // - ALLOW_TRAILING_COMMA: {"x": 1,} trailing commas in objects/arrays
    // These features are CRITICAL for the local LLM (qwen2.5:14b) which frequently
    // produces non-standard JSON.
    private static final ObjectMapper mapper = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .build();

    private final ToolRegistry toolRegistry;
    private final ToolSelector toolSelector;
    private final OwnClawConfig config;

    public ThinkingEngine(ToolRegistry toolRegistry, ToolSelector toolSelector, OwnClawConfig config) {
        this.toolRegistry = toolRegistry;
        this.toolSelector = toolSelector;
        this.config = config;
    }

    /**
     * Decide the next action for the agent based on its current context.
     *
     * @param context    the current agent context (includes trajectory, user message, etc.)
     * @param provider   the LLM provider to use for this reasoning step
     * @return the next action to take
     */
    public AgentAction decideNextAction(AgentContext context, LlmProvider provider) {
        return decideNextActionFull(context, provider).action();
    }

    /**
     * Full variant that returns the prompt, raw LLM output, and parsed action
     * for debug/observability use.
     */
    public ThinkResult decideNextActionFull(AgentContext context, LlmProvider provider) {
        List<LlmMessage> messages = buildMessages(context, provider.name());

        LlmRequestConfig requestConfig = new LlmRequestConfig(
                null,   // use provider default model
                null,   // use provider default temperature
                8192,   // enough for structured action with long response messages
                true,   // JSON mode for structured output
                null    // use provider default read timeout
        );

        try {
            LlmResponse response = provider.chat(messages, requestConfig);
            log.debug("ThinkingEngine LLM response ({} tokens): {}", response.totalTokens(),
                    truncate(response.content(), 200));
            AgentAction action = parseAction(response.content());
            return new ThinkResult(action, messages, response.content(), response.totalTokens());
        } catch (LlmException e) {
            log.error("ThinkingEngine LLM call failed: {}", e.getMessage());
            AgentAction action = new AgentAction(AgentAction.RESPOND,
                    Map.of("message", "I encountered an error while reasoning about this task. Please try again."),
                    "LLM call failed: " + e.getMessage());
            return new ThinkResult(action, messages, "ERROR: " + e.getMessage(), 0);
        }
    }

    /**
     * Build the full message list for the LLM.
     */
    private List<LlmMessage> buildMessages(AgentContext context, String providerName) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(buildSystemPrompt(context, providerName)));

        if ("anthropic".equals(providerName)) {
            // Anthropic: multi-turn trajectory for prefix caching.
            // System prompt is static-only; dynamic context (datetime, tools) goes
            // in conversation messages so the system prompt never changes.
            buildAnthropicMessages(messages, context);
        } else {
            // OpenAI / other: single trajectory message, dynamic content in system prompt
            messages.add(LlmMessage.user(buildUserMessage(context)));
            AgentTrajectory trajectory = context.trajectory();
            if (!trajectory.isEmpty()) {
                messages.add(LlmMessage.user(buildTrajectoryMessage(trajectory)));
            }
        }

        return messages;
    }

    /**
     * Build Anthropic-optimized message list with multi-turn trajectory.
     * <p>
     * Instead of a single trajectory summary message, each action/observation pair
     * becomes an alternating assistant/user turn. This enables Anthropic's prefix
     * caching: the stable conversation prefix (older turns) is cached at 10% cost,
     * and only the latest turn + dynamic context pay full price.
     * <p>
     * Dynamic content (datetime, tools, user prefs) is appended to the last user
     * message, keeping the system prompt 100% static for reliable caching.
     */
    private void buildAnthropicMessages(List<LlmMessage> messages, AgentContext context) {
        messages.add(LlmMessage.user(buildUserMessage(context)));

        AgentTrajectory trajectory = context.trajectory();

        // Filter out _thinking parse failures — they add noise without useful info
        List<AgentTrajectory.Turn> effectiveTurns = new ArrayList<>();
        for (var turn : trajectory.turns()) {
            if (!turn.observation().success() && "_thinking".equals(turn.observation().tool())) {
                continue;
            }
            effectiveTurns.add(turn);
        }

        if (effectiveTurns.isEmpty()) {
            // Step 0 or all-failures: append dynamic context to the user message
            LlmMessage lastMsg = messages.get(messages.size() - 1);
            messages.set(messages.size() - 1, LlmMessage.user(
                    lastMsg.content() + "\n\n---\n" + buildDynamicContext(context)));
            return;
        }

        // Multi-turn: each action/observation becomes assistant/user message pair.
        // Last 2 turns get full output detail; older turns are compressed.
        int fullDetailFrom = Math.max(0, effectiveTurns.size() - 2);
        for (int i = 0; i < effectiveTurns.size(); i++) {
            var turn = effectiveTurns.get(i);
            boolean isFull = i >= fullDetailFrom;
            boolean isLast = i == effectiveTurns.size() - 1;

            // Assistant turn: reconstructed action JSON (what the LLM "said")
            messages.add(LlmMessage.assistant(formatActionForMultiTurn(turn.action(), isFull)));

            // User turn: observation result
            String obsText = formatObservationForMultiTurn(turn, isFull);

            // Append dynamic context to the LAST observation only —
            // this keeps it out of the cached prefix while providing current info.
            if (isLast) {
                obsText += "\n\n---\n" + buildDynamicContext(context);
            }
            messages.add(LlmMessage.user(obsText));
        }
    }

    /**
     * Build dynamic context string (datetime, tools, user preferences, vault).
     * For Anthropic, this goes in conversation messages instead of the system prompt
     * to keep the system prompt 100% static for caching.
     */
    private String buildDynamicContext(AgentContext context) {
        var sb = new StringBuilder();

        sb.append("## Environment\n");
        sb.append("- Platform: ").append(detectPlatform()).append("\n");
        sb.append("- DateTime: ").append(LocalDateTime.now()
                .truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n\n");

        if (context.userPreferences() != null && !context.userPreferences().isBlank()) {
            sb.append("## Preferences\n");
            sb.append(context.userPreferences()).append("\n\n");
        }

        ToolSelector.Selection selection = toolSelector.select(
                context.originalMessage(), context.trajectory());
        sb.append("## Tools\n");
        if (context.trajectory().isEmpty()) {
            // Step 0: full manifest (first exposure — cached in prefix for later steps)
            String manifest = toolRegistry.generateManifest(selection.detailed(), context.credentialKeys());
            sb.append(manifest).append("\n");
            if (!selection.otherNames().isEmpty()) {
                sb.append("\nAlso: ").append(String.join(", ", selection.otherNames())).append("\n");
            }
            if (manifest.isBlank()) {
                sb.append("No tools yet — use skill_create.\n");
            }
        } else {
            // Step 1+: names only (full descriptions cached in prior turns)
            List<String> names = selection.detailed().stream()
                    .sorted(Comparator.comparing(Tool::name))
                    .map(Tool::name)
                    .toList();
            sb.append(String.join(", ", names));
            if (!selection.otherNames().isEmpty()) {
                sb.append(" | also: ").append(String.join(", ", selection.otherNames()));
            }
            sb.append("\n");
        }

        List<String> vaultKeys = context.credentialKeys();
        if (!vaultKeys.isEmpty()) {
            sb.append("\nVault: ").append(String.join(", ", vaultKeys)).append("\n");
        }

        if (!context.trajectory().isEmpty()) {
            sb.append("\nNext action? If done, use 'respond'.");
        }

        // Delegation nudge — injected by AgentLoop when repetitive tool calls are detected
        Object nudge = context.metadata().get("delegationNudge");
        if (nudge instanceof String nudgeMsg && !nudgeMsg.isBlank()) {
            sb.append("\n\nCOST WARNING: ").append(nudgeMsg);
        }

        return sb.toString();
    }

    /**
     * Format an agent action as a JSON string for multi-turn conversation.
     * Reconstructs what the LLM would have generated as its response.
     */
    private String formatActionForMultiTurn(AgentAction action, boolean fullDetail) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            String reasoning = action.reasoning();
            if (reasoning != null && !reasoning.isBlank()) {
                if (!fullDetail && reasoning.length() > 200) {
                    reasoning = reasoning.substring(0, 200) + "...";
                }
                map.put("reasoning", reasoning);
            }
            map.put("tool", action.tool());
            if (action.params() != null && !action.params().isEmpty()) {
                map.put("params", action.params());
            }
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"tool\": \"" + action.tool() + "\"}";
        }
    }

    /**
     * Format a trajectory turn's observation for multi-turn conversation.
     */
    private String formatObservationForMultiTurn(AgentTrajectory.Turn turn, boolean fullDetail) {
        var sb = new StringBuilder();
        sb.append("[").append(turn.action().tool()).append("] ");
        sb.append(turn.observation().success() ? "OK" : "FAILED");
        sb.append(" (").append(turn.observation().durationMs()).append("ms)\n");

        String output = turn.observation().output();
        if (output != null && !output.isBlank()) {
            if (fullDetail || output.length() <= 300) {
                sb.append(output);
            } else {
                // Smart truncation: keep head + tail to preserve context from both ends
                int half = 150;
                sb.append(output, 0, half)
                        .append("\n...[" ).append(output.length()).append(" chars, middle omitted]...\n")
                        .append(output, output.length() - half, output.length());
            }
        }
        return sb.toString();
    }

    /**
     * Build the system prompt. This defines the agent's behavior, available tools,
     * and output format. Completely generic — no domain-specific content.
     */
    private String buildSystemPrompt(AgentContext context, String providerName) {
        // Anthropic: always use the full prompt — the static section is cached by
        // Anthropic's prompt caching (9200 tokens cached, read at 10% cost = ~920
        // effective tokens). The compact prompt broke caching: different prefix meant
        // step 2+ never read the cache created on step 1.
        // OpenAI: has no prompt caching, so the compact prompt on steps 2+ saves ~700
        // real tokens per step.
        if (!context.trajectory().isEmpty() && !"anthropic".equals(providerName)) {
            return buildCompactSystemPrompt(context);
        }

        var sb = new StringBuilder();

        sb.append("You are an autonomous agent. Reason, pick a tool, observe, repeat until done.\n\n");

        // ═══════════════════════════════════════════════════════════════════
        // STATIC SECTION — identical across all requests/tasks/steps.
        // AnthropicProvider caches everything up to CACHE_BOUNDARY_MARKER.
        // ═══════════════════════════════════════════════════════════════════

        // Identity
        sb.append("## Identity\n");
        sb.append("Personal agent running locally. Full system access via Python skills.\n");
        sb.append("ANY system dependency is installable — use system_packages in skill_create.\n");
        sb.append("NEVER refuse or claim a package/tool is unavailable. Use skill_create for any missing capability.\n\n");

        // NOTE: CapabilityResolver hints are handled deterministically in AgentLoop.runLoop()
        // at step 0 — the hint bypasses the ThinkingEngine entirely and synthesizes the
        // skill_create action without any LLM call. By the time the ThinkingEngine runs
        // (step 1+), the skill is already created and visible in the trajectory.

        // Special actions (static — tool descriptions never change)
        sb.append("## Actions\n\n");
        sb.append("respond(message): Final answer.\n\n");
        sb.append("ask_user(message): Ask ONLY when info is missing. Never ask permission — just act.\n\n");

        sb.append("skill_create: Create/update Python skill (code AUTO-GENERATED — specify WHAT not HOW).\n");
        sb.append("  THINK FIRST: anticipate edge cases, required imports, error handling. Rework burns tokens.\n");
        sb.append("  Local execution, full system access. To fix: reuse SAME name (overwrites). NEVER _v2/_fixed/_new.\n");
        sb.append("  name*: lowercase id | description*: behavior spec + edge cases + output format\n");
        sb.append("  parameters*: JSON {key: {type, description, required}} | requirements: pip pkgs (one/line)\n");
        sb.append("  requires_network | has_side_effects | timeout: max secs (default 30)\n");
        sb.append("  credentials: comma-separated vault keys, auto-injected as env vars. NEVER pass values directly.\n");
        sb.append("  system_packages: space-separated apt pkg names \u2192 auto-installed in a container. Use for ANY needed OS binary or library.\n");
        sb.append("  container_image: Docker base image. Optional \u2014 system picks a sensible default and auto-recovers if unavailable.\n\n");

        sb.append("skill_manage(action=read|delete|list|analyze, [name])\n");
        sb.append("credential_manage(action=list|check|store, [key], [value])\n");
        sb.append("memory_manage(action=store|list|delete, [key], [content])\n\n");

        sb.append("schedule_manage:\n");
        sb.append("  action=schedule_once|schedule_recurring|list|cancel|pause|resume\n");
        sb.append("  description: task message | time: natural language | schedule: natural language or Spring cron\n");
        sb.append("  max_runs | task_id (for cancel/pause/resume)\n\n");

        sb.append("delegate: Execute multi-tool plan via FREE local LLM. Zero cloud cost.\n");
        sb.append("  MUST USE for 2+ sequential calls not needing judgment between steps.\n");
        sb.append("  NOT for: skill creation, complex reasoning, judgment-dependent steps.\n");
        sb.append("  goal* | steps*: [{description, tool, params}] | checkpoints | max_steps (default 10)\n\n");

        // Credential rules
        sb.append("## Credentials\n");
        sb.append("Vault values auto-injected as env vars into skills declaring them.\n");
        sb.append("- Declare in skill_create 'credentials' param (exact vault key names).\n");
        sb.append("- All present → create and run. Don't ask user.\n");
        sb.append("- Auth failure with present creds → ask user for the wrong value, update via credential_manage.\n");
        sb.append("- Only ask for credentials NOT in vault.\n\n");

        sb.append("## Memory\n");
        sb.append("Facts persist across conversations. 'Remember this' → store immediately.\n\n");

        // Output format
        sb.append("## Output\n");
        sb.append("Single JSON: {\"reasoning\": \"...\", \"tool\": \"name\", \"params\": {...}}\n");
        sb.append("CRITICAL: Keep 'reasoning' to 1-2 sentences. Long reasoning wastes tokens and risks truncation.\n");
        sb.append("For respond: put ALL content in params.message, NOT in reasoning.\n\n");

        // Behavioral guidelines + cost + self-improvement combined
        sb.append("## Rules\n");
        sb.append("- No tools needed → respond directly. Never fabricate outputs.\n");
        sb.append("- On failure: diagnose WHY, then try fundamentally different approach. Never repeat failed actions.\n");
        sb.append("- Skill errors: fix via skill_create (SAME name). Never _v2/_fixed.\n");
        sb.append("- 2+ sequential calls → ALWAYS delegate (free). After creating/fixing skill → delegate batch.\n");
        sb.append("- No suitable tool → create one. Poor results → read skill code, overwrite fix.\n");
        sb.append("- Explore thoroughly before 'not found'. Search the internet if stuck.\n");
        sb.append("- Respond in user's language. Search/selectors in content's language.\n");
        sb.append("- Outputs: clean text. Extract file content (PDF/DOCX/CSV), don't just report links.\n");
        sb.append("- Garbled text → encoding bug, fix the tool.\n");
        sb.append("- Partial USEFUL result beats empty failure. Each step must make new progress.\n");
        sb.append("- NEVER write multi-line code via shell_exec/python3 -c. Use skill_create.\n");
        sb.append("- Need an OS binary or system library? Add it to system_packages in skill_create. NEVER say a package is unavailable.\n");

        // Anthropic: return static-only system prompt. Dynamic content (datetime,
        // tools, user prefs) goes in conversation messages via buildAnthropicMessages()
        // to keep the system prompt identical across all steps — enabling both
        // system-level AND conversation-prefix caching.
        if ("anthropic".equals(providerName)) {
            return sb.toString();
        }

        // ═══════════════════════════════════════════════════════════════════
        // DYNAMIC SECTION — changes per request/task/step.
        // Everything below this marker is NOT cached by Anthropic.
        // ═══════════════════════════════════════════════════════════════════
        sb.append(CACHE_BOUNDARY_MARKER);

        // Environment context (dynamic — changes every request)
        sb.append("## Environment\n");
        sb.append("- Platform: ").append(detectPlatform()).append("\n");
        // Truncate to minute precision — seconds change between agent steps (which
        // happen seconds apart) and would invalidate the Anthropic conversation history
        // cache. Minute precision is stable enough for the LLM while maximizing cache hits.
        sb.append("- DateTime: ").append(LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n\n");

        // User preferences (if any)
        if (context.userPreferences() != null && !context.userPreferences().isBlank()) {
            sb.append("## User Preferences\n");
            sb.append(context.userPreferences()).append("\n\n");
        }

        // Smart tool selection — include only relevant tools in detail
        ToolSelector.Selection selection = toolSelector.select(
                context.originalMessage(), context.trajectory());

        sb.append("## Available Tools\n");
        String manifest = toolRegistry.generateManifest(selection.detailed(), context.credentialKeys());
        sb.append(manifest).append("\n");

        // If some tools were omitted, list them by name so the LLM knows they exist
        if (!selection.otherNames().isEmpty()) {
            sb.append("\n## Other Available Tools (use by name if needed)\n");
            sb.append(String.join(", ", selection.otherNames())).append("\n");
        }

        if (manifest.isBlank()) {
            sb.append("\nNo tools yet. Use skill_create as first action.\n");
        }
        sb.append("\n");

        // Dynamic vault contents
        List<String> vaultKeys = context.credentialKeys();
        if (!vaultKeys.isEmpty()) {
            sb.append("Vault contains: ").append(String.join(", ", vaultKeys)).append("\n\n");
        }

        // Delegation nudge — injected by AgentLoop when repetitive tool calls are detected
        Object nudge = context.metadata().get("delegationNudge");
        if (nudge instanceof String nudgeMsg && !nudgeMsg.isBlank()) {
            sb.append("COST WARNING: ").append(nudgeMsg).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * Condensed system prompt for reasoning steps 2+.
     * Omits verbose behavioral guidelines, detailed parameter descriptions,
     * and instructional sections that the LLM has already seen on step 1.
     * Saves ~700 cloud tokens per step.
     */
    private String buildCompactSystemPrompt(AgentContext context) {
        var sb = new StringBuilder(2048);

        sb.append("Autonomous agent. Reason, pick tools, observe, repeat. Never refuse. skill_create for new capabilities.\n\n");

        // ── CACHE BOUNDARY ── static preamble above is cacheable
        sb.append(CACHE_BOUNDARY_MARKER);

        // Environment (always — dynamic datetime)
        sb.append("## Environment\n");
        sb.append("- Platform: ").append(detectPlatform()).append("\n");
        sb.append("- DateTime: ").append(LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n\n");

        // User preferences (always — dynamic per user)
        if (context.userPreferences() != null && !context.userPreferences().isBlank()) {
            sb.append("## User Preferences\n");
            sb.append(context.userPreferences()).append("\n\n");
        }

        // Available Tools (always — dynamic tool selection based on trajectory)
        ToolSelector.Selection selection = toolSelector.select(
                context.originalMessage(), context.trajectory());
        sb.append("## Tools\n");
        String manifest = toolRegistry.generateManifest(selection.detailed(), context.credentialKeys());
        sb.append(manifest).append("\n");
        if (!selection.otherNames().isEmpty()) {
            sb.append("Also: ").append(String.join(", ", selection.otherNames())).append("\n");
        }
        if (manifest.isBlank()) {
            sb.append("No tools yet — use skill_create.\n");
        }
        sb.append("\n");

        // Compact special actions — parameter names only, one line each
        sb.append("## Actions\n");
        sb.append("respond(message) | ask_user(message)\n");
        sb.append("skill_create(name, description, parameters[JSON], [requirements], [credentials], [system_packages→container], [timeout])\n");
        sb.append("Fix skill: reuse SAME name. NEVER _v2/_fixed/_new.\n");
        sb.append("skill_manage(action=read|delete|list|analyze, [name])\n");
        sb.append("credential_manage(action=list|check|store, [key], [value])\n");
        sb.append("memory_manage(action=store|list|delete, [key], [content])\n");
        sb.append("schedule_manage(action=schedule_once|schedule_recurring|list|cancel|pause|resume, [description], [time], [schedule], [max_runs], [task_id])\n");
        sb.append("delegate(goal, steps[{description,tool,params}], [checkpoints], [max_steps]) — FREE local LLM. MUST USE for 2+ sequential calls.\n\n");

        // Problem-solving nudge (compact version of the full prompt's ## Problem Solving)
        sb.append("Stuck? Think deeper, search the internet, try a fundamentally different approach. Never repeat what failed.\n");
        sb.append("Need OS tools/binaries? system_packages in skill_create auto-installs any apt package in a container.\n\n");

        // Credential reminder in compact prompt
        List<String> vaultKeys = context.credentialKeys();
        if (!vaultKeys.isEmpty()) {
            sb.append("Vault: ").append(String.join(", ", vaultKeys)).append("\n");
        }
        sb.append("Credentials auto-injected. Only ask for missing ones. Declare in 'credentials' param.\n\n");

        // Output format (always needed)
        sb.append("Output: {\"reasoning\": \"...\", \"tool\": \"name\", \"params\": {...}}\n");

        // Delegation nudge — injected by AgentLoop when repetitive tool calls are detected
        Object nudge = context.metadata().get("delegationNudge");
        if (nudge instanceof String nudgeMsg && !nudgeMsg.isBlank()) {
            sb.append("\nCOST WARNING: ").append(nudgeMsg).append("\n");
        }

        return sb.toString();
    }

    /**
     * Build the user message containing the original request and conversation context.
     */
    private String buildUserMessage(AgentContext context) {
        var sb = new StringBuilder();

        // Conversation summary for context
        if (context.conversationSummary() != null && !context.conversationSummary().isBlank()) {
            sb.append("## Prior Context\n");
            sb.append(context.conversationSummary()).append("\n\n");
        }

        // Relevant past experiences from memory
        Object memories = context.metadata().get("relevantMemories");
        if (memories instanceof String memStr && !memStr.isBlank()) {
            sb.append("## Past Experience\n");
            sb.append(memStr).append("\n\n");
        }

        sb.append("## Task\n");
        sb.append(context.originalMessage());

        return sb.toString();
    }

    /**
     * Build a message summarizing the trajectory of past actions in this execution.
     */
    private String buildTrajectoryMessage(AgentTrajectory trajectory) {
        var sb = new StringBuilder();
        sb.append("## History\n");
        sb.append(trajectory.toPromptSummary());
        sb.append("Next action? If done, use 'respond'.");
        return sb.toString();
    }

    // Regex that matches multi-line block comments in JSON
    private static final java.util.regex.Pattern BLOCK_COMMENT =
            java.util.regex.Pattern.compile("/\\*.*?\\*/", java.util.regex.Pattern.DOTALL);

    /**
     * Strip JavaScript-style comments from an LLM-produced JSON string.
     * LLMs sometimes add // annotations in JSON which Jackson rejects.
     */
    private String stripJsonComments(String json) {
        if (json == null) return json;
        // Remove block comments first, then line comments
        json = BLOCK_COMMENT.matcher(json).replaceAll("");
        // Only strip // comments that are NOT inside a quoted string.
        // Simple heuristic: split by lines and strip trailing // that aren't inside quotes.
        var sb = new StringBuilder();
        for (String line : json.split("\n", -1)) {
            sb.append(stripLineComment(line)).append('\n');
        }
        return sb.toString();
    }

    /** Remove trailing // comment from a single line, being careful not to strip inside string values. */
    private String stripLineComment(String line) {
        boolean inString = false;
        char prev = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' && prev != '\\') {
                inString = !inString;
            } else if (!inString && c == '/' && prev == '/') {
                return line.substring(0, i - 1);
            }
            prev = c;
        }
        return line;
    }

    /**
     * Parse the LLM's JSON response into an AgentAction.
     * Handles common LLM output quirks (code fences, comments, extra text, etc.).
     */
    AgentAction parseAction(String raw) {
        if (raw == null || raw.isBlank()) {
            return fallbackResponse("Empty response from reasoning engine.");
        }

        String cleaned = LlmOutputUtils.stripCodeFences(raw.strip());

        // Try to parse as JSON. Use Jackson's streaming parser to find the first
        // valid JSON object — handles nested braces, escaped chars, etc. correctly.
        // If no valid JSON object can be parsed, the LLM produced natural language
        // which is a direct response, not a parse failure.
        Map<String, Object> parsed = tryParseJsonObject(cleaned);
        if (parsed == null) {
            log.warn("ThinkingEngine: no valid JSON object in LLM response, treating as direct response");
            return new AgentAction(AgentAction.RESPOND,
                    Map.of("message", raw.strip()),
                    "LLM did not produce structured output; delivering raw response");
        }

        try {

            // Try multiple field names that LLMs commonly use for tool selection
            String tool = getStringField(parsed, "tool");
            if (tool == null || tool.isBlank()) tool = getStringField(parsed, "action");
            if (tool == null || tool.isBlank()) tool = getStringField(parsed, "name");
            if (tool == null || tool.isBlank()) tool = getStringField(parsed, "function");
            if (tool == null || tool.isBlank()) tool = getStringField(parsed, "command");

            // Try nested structures: {"action": {"tool": "..."}}
            if ((tool == null || tool.isBlank()) && parsed.get("action") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> actionMap = (Map<String, Object>) parsed.get("action");
                tool = getStringField(actionMap, "tool");
                if (tool == null || tool.isBlank()) tool = getStringField(actionMap, "name");
            }

            String reasoning = getStringField(parsed, "reasoning");
            if (reasoning == null || reasoning.isBlank()) reasoning = getStringField(parsed, "thought");
            if (reasoning == null || reasoning.isBlank()) reasoning = getStringField(parsed, "thoughts");
            if (reasoning == null || reasoning.isBlank()) reasoning = getStringField(parsed, "thinking");

            @SuppressWarnings("unchecked")
            Map<String, Object> params = parsed.containsKey("params") && parsed.get("params") instanceof Map
                    ? (Map<String, Object>) parsed.get("params")
                    : parsed.containsKey("parameters") && parsed.get("parameters") instanceof Map
                        ? (Map<String, Object>) parsed.get("parameters")
                        : parsed.containsKey("arguments") && parsed.get("arguments") instanceof Map
                            ? (Map<String, Object>) parsed.get("arguments")
                            : Map.of();

            if (tool == null || tool.isBlank()) {
                // If there's a "message" field at root level, treat as response
                String message = getStringField(parsed, "message");
                if (message == null || message.isBlank()) message = getStringField(parsed, "response");
                if (message == null || message.isBlank()) message = getStringField(parsed, "content");
                if (message == null || message.isBlank()) message = getStringField(parsed, "text");

                // Handle "status report" pattern: {"ok": false, "error": "...", "next_step": "..."}
                // The LLM is producing diagnostic JSON instead of a tool call — still useful content
                if (message == null || message.isBlank()) {
                    String error = getStringField(parsed, "error");
                    String reason = getStringField(parsed, "reason");
                    String nextStep = getStringField(parsed, "next_step");
                    Object errors = parsed.get("errors");
                    StringBuilder statusReport = new StringBuilder();
                    if (error != null && !error.isBlank()) statusReport.append(error);
                    if (reason != null && !reason.isBlank()) statusReport.append(reason);
                    if (errors instanceof List<?> errList && !errList.isEmpty()) {
                        statusReport.append(errList.stream()
                                .map(Object::toString)
                                .collect(java.util.stream.Collectors.joining("; ")));
                    }
                    if (nextStep != null && !nextStep.isBlank()) {
                        statusReport.append("\n Next step: ").append(nextStep);
                    }
                    if (!statusReport.isEmpty()) {
                        message = statusReport.toString();
                    }
                }

                if (message != null && !message.isBlank()) {
                    return new AgentAction(AgentAction.RESPOND, Map.of("message", message),
                            reasoning != null ? reasoning : "Direct response");
                }
                log.warn("ThinkingEngine: no 'tool' field in parsed JSON. Keys present: {}",
                        parsed.keySet());
                log.warn("ThinkingEngine: raw parsed JSON: {}",
                        truncate(cleaned, 500));
                return fallbackResponse("I had trouble deciding what to do. Let me try again.");
            }

            return new AgentAction(tool, params, reasoning != null ? reasoning : "");
        } catch (Exception e) {
            log.warn("ThinkingEngine: failed to parse LLM JSON output: {}", e.getMessage());
            // Last resort: treat it as a direct response
            return new AgentAction(AgentAction.RESPOND,
                    Map.of("message", raw.strip()),
                    "Failed to parse structured output; delivering raw response");
        }
    }

    private AgentAction fallbackResponse(String message) {
        return new AgentAction(AgentAction.RESPOND, Map.of("message", message), "Fallback response");
    }

    private String getStringField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private String detectPlatform() {
        String os = System.getProperty("os.name", "unknown").toLowerCase();
        if (os.contains("win")) return "Windows";
        if (os.contains("mac")) return "macOS";
        if (os.contains("linux")) return "Linux";
        return os;
    }

    /**
     * Try to parse the first valid JSON object from a string that may contain
     * surrounding natural language text. Uses Jackson's streaming parser to
     * correctly handle nested braces, escaped characters, and strings containing
     * braces — avoiding false matches on things like {CURRENT_YEAR}.
     *
     * Strategy:
     *   1. Try parsing the whole string as JSON (common case — LLM followed instructions).
     *   2. Try each '{' position as a potential JSON start; use Jackson's streaming
     *      parser which reads exactly one value and stops (tolerates trailing text).
     *   3. If nothing parses, return null (not a failure — LLM wrote prose).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> tryParseJsonObject(String text) {
        if (text == null || text.isBlank()) return null;

        // Strip JS-style comments before any parsing attempt
        String stripped = stripJsonComments(text);

        // Fast path: entire string is valid JSON
        try {
            Object result = mapper.readValue(stripped, Object.class);
            if (result instanceof Map) return (Map<String, Object>) result;
        } catch (Exception ignored) {}

        // Scan for '{' and try parsing from each candidate position.
        // Jackson's streaming parser reads exactly one JSON value and stops,
        // so trailing text (natural language after the JSON) is not a problem.
        var factory = mapper.getFactory();
        int searchFrom = 0;
        while (searchFrom < stripped.length()) {
            int bracePos = stripped.indexOf('{', searchFrom);
            if (bracePos < 0) break;

            try (var parser = factory.createParser(stripped.substring(bracePos))) {
                Object result = mapper.readValue(parser, Object.class);
                if (result instanceof Map) return (Map<String, Object>) result;
            } catch (Exception ignored) {}

            searchFrom = bracePos + 1;
        }

        return null;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
