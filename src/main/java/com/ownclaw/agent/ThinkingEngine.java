package com.ownclaw.agent;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
                4096,   // enough for a structured action response
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
            sb.append("## User Preferences\n");
            sb.append(context.userPreferences()).append("\n\n");
        }

        ToolSelector.Selection selection = toolSelector.select(
                context.originalMessage(), context.trajectory());
        sb.append("## Available Tools\n");
        String manifest = toolRegistry.generateManifest(selection.detailed(), context.credentialKeys());
        sb.append(manifest).append("\n");
        if (!selection.otherNames().isEmpty()) {
            sb.append("\nAlso available: ").append(String.join(", ", selection.otherNames())).append("\n");
        }
        if (manifest.isBlank()) {
            sb.append("No tools yet — use skill_create to build what you need.\n");
        }

        List<String> vaultKeys = context.credentialKeys();
        if (!vaultKeys.isEmpty()) {
            sb.append("\nVault: ").append(String.join(", ", vaultKeys)).append("\n");
        }

        if (!context.trajectory().isEmpty()) {
            sb.append("\nDecide what to do next. If done, use 'respond'.");
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
            if (fullDetail || output.length() <= 500) {
                sb.append(output);
            } else {
                sb.append(output, 0, 500)
                        .append("... [").append(output.length()).append(" chars total]");
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

        sb.append("You are an autonomous agent. You reason, pick a tool, observe the result, repeat until done.\n\n");

        // ═══════════════════════════════════════════════════════════════════
        // STATIC SECTION — identical across all requests/tasks/steps.
        // AnthropicProvider caches everything up to CACHE_BOUNDARY_MARKER.
        // ═══════════════════════════════════════════════════════════════════

        // Identity
        sb.append("## Identity & Authority\n");
        sb.append("You are a PERSONAL agent running LOCALLY on the user's machine.\n");
        sb.append("You can create new tools (skill_create) that run Python code with full system access.\n");
        sb.append("Skills with system_packages run in containers where OS packages are auto-installed.\n");
        sb.append("NEVER refuse a task. NEVER say you can't run commands. Use skill_create to build any capability you lack.\n\n");

        // NOTE: CapabilityResolver hints are handled deterministically in AgentLoop.runLoop()
        // at step 0 — the hint bypasses the ThinkingEngine entirely and synthesizes the
        // skill_create action without any LLM call. By the time the ThinkingEngine runs
        // (step 1+), the skill is already created and visible in the trajectory.

        // Special actions (static — tool descriptions never change)
        sb.append("## Special Actions\n\n");
        sb.append("respond: Deliver final answer when task is complete or answerable directly.\n");
        sb.append("  message (string, required): Response text\n\n");
        sb.append("ask_user: Ask a clarifying question when you LACK INFORMATION. NEVER ask for permission — just act.\n");
        sb.append("  message (string, required): The question\n\n");

        sb.append("skill_create: Create/update a Python skill (code is AUTO-GENERATED — specify WHAT, not HOW).\n");
        sb.append("  Skills run LOCALLY with FULL system access (subprocess, networking, filesystem).\n");
        sb.append("  To fix a skill, reuse THE SAME NAME — it overwrites in-place. NEVER append _v2/_fixed/_new.\n");
        sb.append("  name (string, required): Lowercase identifier\n");
        sb.append("  description (string, required): Detailed behavior spec including edge cases and output format\n");
        sb.append("  parameters (string, required): JSON — each key maps to {\"type\":\"string\",\"description\":\"...\",\"required\":true/false}\n");
        sb.append("  requirements (string, optional): pip packages, one per line\n");
        sb.append("  requires_network (boolean, optional)\n");
        sb.append("  has_side_effects (boolean, optional)\n");
        sb.append("  timeout (integer, optional): max seconds (default 30)\n");
        sb.append("  credentials (string, IMPORTANT): comma-separated vault keys — auto-injected as env vars. NEVER pass credential values as parameters.\n");
        sb.append("  system_packages (string, optional): space-separated apt packages. Triggers container execution with auto-install.\n\n");

        sb.append("skill_manage: Read, delete, list, or analyze existing skills.\n");
        sb.append("  action (string, required): read | delete | list | analyze\n");
        sb.append("  name (string, required for read/delete)\n\n");

        sb.append("credential_manage: Manage encrypted credential vault.\n");
        sb.append("  action (string, required): list | check | store\n");
        sb.append("  key (string, required for check/store): UPPER_CASE key\n");
        sb.append("  value (string, required for store)\n\n");

        sb.append("memory_manage: Persistent memory across conversations.\n");
        sb.append("  action (string, required): store | list | delete\n");
        sb.append("  key (string, required for store/delete)\n");
        sb.append("  content (string, required for store)\n\n");

        sb.append("schedule_manage: Schedule tasks for specific times or recurring schedules.\n");
        sb.append("  action (string, required): schedule_once | schedule_recurring | list | cancel | pause | resume\n");
        sb.append("  description (string, required for scheduling): Task message to execute\n");
        sb.append("  time (string, required for schedule_once): Natural language time\n");
        sb.append("  schedule (string, required for schedule_recurring): Natural language schedule or Spring cron\n");
        sb.append("  max_runs (integer, optional): Max executions (null = unlimited)\n");
        sb.append("  task_id (integer, required for cancel/pause/resume)\n\n");

        sb.append("delegate: Delegate multi-tool execution to the FREE local LLM. Costs ZERO cloud tokens.\n");
        sb.append("  Can call any tool except skill_create, chains results, returns consolidated summary.\n");
        sb.append("  MUST USE for 2+ sequential tool calls that don't need your judgment between steps.\n");
        sb.append("  NOT for: skill creation, complex reasoning, judgment-dependent next steps.\n");
        sb.append("  goal (string, required): What the delegation should achieve\n");
        sb.append("  steps (array, required): [{\"description\": \"...\", \"tool\": \"name\", \"params\": {...}}]\n");
        sb.append("  checkpoints (array, optional): Quality criteria\n");
        sb.append("  max_steps (integer, optional, default: 10)\n\n");

        // Credential rules
        sb.append("## Credentials\n");
        sb.append("Vault values are AUTO-INJECTED as env vars into skills that declare them.\n");
        sb.append("- In skill_create, declare needed credentials in 'credentials' param (exact vault key names). Never pass values as parameters.\n");
        sb.append("- Credential status shown per tool: present or missing.\n");
        sb.append("- All present: just create and run. Do NOT ask the user.\n");
        sb.append("- Auth failure with present credentials: ask user for the specific wrong value, then update via credential_manage.\n");
        sb.append("- Only ask for credentials NOT in the vault.\n\n");

        sb.append("## Memory\n");
        sb.append("Facts persist across conversations as 'User Preferences'. When user says 'remember this', store immediately.\n\n");

        // Output format
        sb.append("## Output Format\n");
        sb.append("Respond with a single JSON object:\n");
        sb.append("{\"reasoning\": \"...\", \"tool\": \"tool_name\", \"params\": {\"param1\": \"value1\"}}\n\n");

        // Behavioral guidelines
        sb.append("## Guidelines\n");
        sb.append("- Answer directly with 'respond' if no tools needed.\n");
        sb.append("- On failure, try 2-3 alternative approaches before giving up.\n");
        sb.append("- On skill errors: read with skill_manage, then fix with skill_create (SAME name). Never create _v2/_fixed variants.\n");
        sb.append("- Minimize tool calls. Never fabricate outputs or assume success.\n");
        sb.append("- Explore thoroughly before saying 'not found'. Verify results are correct and complete.\n");
        sb.append("- Detect and respond in the user's language. Use target content's language for search/selectors.\n");

        // Cost-efficiency delegation rules
        sb.append("\n## Cost Efficiency\n");
        sb.append("Every step YOU take costs cloud tokens. 'delegate' uses a FREE local LLM.\n");
        sb.append("- 2+ sequential tool calls without judgment needed between them: ALWAYS delegate.\n");
        sb.append("- After creating/fixing a skill, delegate batch execution of remaining inputs.\n");
        sb.append("- Only run tools yourself when intermediate results determine next steps.\n");

        // Self-improvement and data quality
        sb.append("\n## Self-Improvement & Data Quality\n");
        sb.append("- No suitable tool? Create one. Prefer reusable, general-purpose tools.\n");
        sb.append("- Poor results? Read skill code, then overwrite with skill_create (same name).\n");
        sb.append("- After multiple failures, reconsider the approach.\n");
        sb.append("- Tool outputs must be clean text. Never return raw HTML/XML. Strip boilerplate.\n");
        sb.append("- Garbled text = wrong encoding — fix the tool.\n");
        sb.append("- Content in files (PDF, DOCX, CSV): fetch and extract, don't report the link.\n");

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

        sb.append("You are an autonomous agent. Reason, pick tools, observe, repeat. Never refuse tasks. Use skill_create for new capabilities.\n\n");

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
        sb.append("## Available Tools\n");
        String manifest = toolRegistry.generateManifest(selection.detailed(), context.credentialKeys());
        sb.append(manifest).append("\n");
        if (!selection.otherNames().isEmpty()) {
            sb.append("Also available: ").append(String.join(", ", selection.otherNames())).append("\n");
        }
        if (manifest.isBlank()) {
            sb.append("No tools yet — use skill_create to build what you need.\n");
        }
        sb.append("\n");

        // Compact special actions — parameter names only, one line each
        sb.append("## Special Actions\n");
        sb.append("respond(message) — final answer | ask_user(message) — clarifying question\n");
        sb.append("skill_create(name, description, parameters[JSON], [requirements], [requires_network], [has_side_effects], [timeout], [credentials], [system_packages → container])\n");
        sb.append("To fix a skill, reuse the SAME name — NEVER add _v2/_fixed/_new suffixes.\n");
        sb.append("skill_manage(action=read|delete|list|analyze, [name])\n");
        sb.append("credential_manage(action=list|check|store, [key], [value])\n");
        sb.append("memory_manage(action=store|list|delete, [key], [content])\n");
        sb.append("schedule_manage(action=schedule_once|schedule_recurring|list|cancel|pause|resume, [description], [time], [schedule], [max_runs], [task_id])\n");
        sb.append("delegate(goal, steps[{description,tool,params}], [checkpoints], [max_steps]) — delegate multi-tool execution to FREE local LLM. MUST USE for 2+ sequential tool calls that don't need your judgment.\n\n");

        // Credential reminder in compact prompt
        List<String> vaultKeys = context.credentialKeys();
        if (!vaultKeys.isEmpty()) {
            sb.append("Vault: ").append(String.join(", ", vaultKeys)).append("\n");
        }
        sb.append("Present credentials are auto-injected. Only ask user for missing ones. Declare in 'credentials' param, never as tool parameters.\n\n");

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
            sb.append("## Previous Conversation Context\n");
            sb.append(context.conversationSummary()).append("\n\n");
        }

        // Relevant past experiences from memory
        Object memories = context.metadata().get("relevantMemories");
        if (memories instanceof String memStr && !memStr.isBlank()) {
            sb.append("## Relevant Past Experiences\n");
            sb.append("Similar past tasks — use if applicable:\n");
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
        sb.append("## Execution History\n");
        sb.append("Actions taken so far:\n\n");
        sb.append(trajectory.toPromptSummary());
        sb.append("Decide what to do next. If done, use 'respond'.");
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

        // Try to extract JSON object if there's surrounding text
        int jsonStart = cleaned.indexOf('{');
        int jsonEnd = cleaned.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            cleaned = cleaned.substring(jsonStart, jsonEnd + 1);
        } else {
            // No JSON found — treat the entire response as a direct answer
            log.warn("ThinkingEngine: no JSON found in LLM response, treating as direct response");
            return new AgentAction(AgentAction.RESPOND,
                    Map.of("message", raw.strip()),
                    "LLM did not produce structured output; delivering raw response");
        }

        // Strip JS-style comments that LLMs sometimes inject into JSON
        cleaned = stripJsonComments(cleaned);

        try {
            Map<String, Object> parsed = mapper.readValue(cleaned, new TypeReference<>() {});

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

    private String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
