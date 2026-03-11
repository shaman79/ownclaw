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
        List<LlmMessage> messages = buildMessages(context);

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
    private List<LlmMessage> buildMessages(AgentContext context) {
        List<LlmMessage> messages = new ArrayList<>();

        // System prompt
        messages.add(LlmMessage.system(buildSystemPrompt(context)));

        // User message with context
        messages.add(LlmMessage.user(buildUserMessage(context)));

        // If there's a trajectory, include it as assistant+user turns for natural conversation flow
        AgentTrajectory trajectory = context.trajectory();
        if (!trajectory.isEmpty()) {
            // Add trajectory as a single user message summarizing past actions
            messages.add(LlmMessage.user(buildTrajectoryMessage(trajectory)));
        }

        return messages;
    }

    /**
     * Build the system prompt. This defines the agent's behavior, available tools,
     * and output format. Completely generic — no domain-specific content.
     */
    private String buildSystemPrompt(AgentContext context) {
        // Always use the full prompt — the static section is cached by Anthropic's
        // prompt caching (see AnthropicProvider). The compact prompt was saving ~700
        // tokens/step but broke caching entirely: its static prefix (~25 tokens) is
        // below Anthropic's 1024-token caching minimum, and the different prefix
        // prevented step 2+ from reading the 9200-token cache created on step 1.
        // Full prompt + cache reads (10% cost) is far cheaper than compact + cache misses.

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
        sb.append("## Special Actions\n");
        sb.append("Always available:\n\n");
        sb.append("respond: Deliver final answer. Use when task is complete or answerable directly.\n");
        sb.append("  message (string, required): Response text\n\n");
        sb.append("ask_user: Ask a clarifying question when you LACK INFORMATION (e.g. missing parameters, ambiguous scope).\n");
        sb.append("  message (string, required): The question\n");
        sb.append("  NEVER use ask_user to request permission or confirm before acting. Just act.\n\n");

        sb.append("skill_create: Create/update a Python skill (code is AUTO-GENERATED — specify WHAT, not HOW).\n");
        sb.append("  Skills are Python scripts running LOCALLY on the user's machine with FULL system access.\n");
        sb.append("  They can run shell commands (subprocess), ANYTHING Python can do.\n");
        sb.append("  IMPORTANT: To fix a broken skill, call skill_create with THE SAME NAME — it overwrites in-place.\n");
        sb.append("  NEVER append _v2, _fixed, _new, _updated or any suffix. One skill = one name, always.\n");
        sb.append("  name (string, required): Lowercase identifier (e.g. 'web_fetch', 'network_scanner', 'shell_exec')\n");
        sb.append("  description (string, required): Detailed behavior spec including edge cases and output format\n");
        sb.append("  parameters (string, required): JSON — each key maps to {\"type\":\"string\",\"description\":\"...\",\"required\":true/false}\n");
        sb.append("  requirements (string, optional): pip packages, one per line\n");
        sb.append("  requires_network (boolean, optional): needs internet?\n");
        sb.append("  has_side_effects (boolean, optional): modifies files, sends emails, etc.?\n");
        sb.append("  timeout (integer, optional): max seconds (default 30)\n");
        sb.append("  credentials (string, IMPORTANT): comma-separated credential keys (e.g. 'IMAP_HOST,IMAP_USER,IMAP_PASS,IMAP_PORT') — auto-injected as env vars at runtime\n");
        sb.append("    ALWAYS declare credentials here — NEVER accept credential values as regular tool parameters.\n");
        sb.append("    Check ✓/✗ marks in Available Tools to see which credentials the user already has stored.\n");
        sb.append("  system_packages (string, optional): space-separated OS packages (apt) needed by the skill (e.g. 'nmap net-tools iputils-ping').\n");
        sb.append("    When specified, the skill runs inside a Docker/Podman container where these packages are auto-installed — no sudo needed.\n");
        sb.append("    Use this for tools like nmap, traceroute, tcpdump, ffmpeg, imagemagick, etc. that are not pip-installable.\n\n");

        sb.append("skill_manage: Read, delete, list, or analyze existing skills.\n");
        sb.append("  action (string, required): 'read', 'delete', 'list', or 'analyze'\n");
        sb.append("  name (string, required for read/delete): Skill name\n\n");

        sb.append("credential_manage: Manage encrypted credential vault (passwords, API keys, tokens).\n");
        sb.append("  action (string, required): 'list', 'check', or 'store'\n");
        sb.append("  key (string, required for check/store): Credential key (UPPER_CASE, e.g. IMAP_PASS)\n");
        sb.append("  value (string, required for store): Value to encrypt and store\n\n");

        sb.append("memory_manage: Persistent memory across conversations.\n");
        sb.append("  action (string, required): 'store', 'list', or 'delete'\n");
        sb.append("  key (string, required for store/delete): Short identifier (e.g. 'timezone', 'email_style')\n");
        sb.append("  content (string, required for store): Fact or instruction to remember\n\n");

        sb.append("schedule_manage: Schedule tasks to run at specific times or on recurring schedules.\n");
        sb.append("  action (string, required): 'schedule_once', 'schedule_recurring', 'list', 'cancel', 'pause', or 'resume'\n");
        sb.append("  description (string, required for schedule_once/schedule_recurring): The task message to execute when the time comes\n");
        sb.append("  time (string, required for schedule_once): Natural language time (e.g. 'in 30 minutes', 'tomorrow at 9am', 'at 14:30')\n");
        sb.append("  schedule (string, required for schedule_recurring): Natural language schedule (e.g. 'every day at 11:00', 'every monday at 9am', 'every 30 minutes') or Spring cron expression\n");
        sb.append("  max_runs (integer, optional for schedule_recurring): Maximum number of executions (null = unlimited)\n");
        sb.append("  task_id (integer, required for cancel/pause/resume): The scheduled task ID\n\n");

        sb.append("delegate: Delegate a multi-tool task to the local LLM executor.\n");
        sb.append("  Use this to offload work that involves EXECUTING EXISTING TOOLS — the local LLM follows your plan.\n");
        sb.append("  The local executor can call any available tool except skill_create, chain results between steps,\n");
        sb.append("  and return a consolidated summary.\n");
        sb.append("  IDEAL for: running multiple tools in sequence (e.g. fetch 3 URLs), data collection across sources,\n");
        sb.append("    routine multi-step execution, parallel-like batch processing.\n");
        sb.append("  NOT suitable for: skill creation (always use skill_create yourself), complex reasoning,\n");
        sb.append("    tasks requiring your judgment to decide next steps based on intermediate results.\n");
        sb.append("  goal (string, required): What the delegation should achieve\n");
        sb.append("  steps (array, required): Ordered list of tool calls. Each step: {\"description\": \"...\", \"tool\": \"tool_name\", \"params\": {...}}\n");
        sb.append("  checkpoints (array, optional): Quality criteria, e.g. [\"All data fetched\", \"Text is readable\"]\n");
        sb.append("  max_steps (integer, optional): Max executor steps including retries (default: 10)\n");
        sb.append("  Example:\n");
        sb.append("  {\"tool\": \"delegate\", \"params\": {\n");
        sb.append("    \"goal\": \"Fetch lunch menus from 3 restaurants\",\n");
        sb.append("    \"steps\": [\n");
        sb.append("      {\"description\": \"Fetch menu from Restaurant A\", \"tool\": \"web_fetch\", \"params\": {\"url\": \"...\"}},\n");
        sb.append("      {\"description\": \"Fetch menu from Restaurant B\", \"tool\": \"web_fetch\", \"params\": {\"url\": \"...\"}}\n");
        sb.append("    ],\n");
        sb.append("    \"checkpoints\": [\"All menus fetched successfully\"],\n");
        sb.append("    \"max_steps\": 8\n");
        sb.append("  }}\n\n");

        // Credential rules (static — the actual vault contents are dynamic, added after boundary)
        sb.append("## Credential Vault\n");
        sb.append("AES-256-GCM encrypted storage. Credential values are AUTO-INJECTED as env vars into skills that declare them.\n");
        sb.append("CRITICAL credential rules:\n");
        sb.append("- When creating skills, ALWAYS declare needed credentials in the 'credentials' parameter.\n");
        sb.append("  Match the exact key names from the vault (e.g. credentials='IMAP_HOST,IMAP_USER,IMAP_PASS,IMAP_PORT').\n");
        sb.append("  Do NOT add credential values as tool parameters — they are injected automatically from the vault.\n");
        sb.append("- Tools above show credential status: ✓ = stored, ✗ = missing.\n");
        sb.append("- If all required credentials are ✓ (or listed in vault): just CREATE the skill and RUN it. Do NOT ask the user.\n");
        sb.append("- If a tool with ✓ credentials fails (auth/connection error): the stored VALUE might be wrong.\n");
        sb.append("  Ask the user ONLY for the specific value that seems wrong, then update with credential_manage(action='store').\n");
        sb.append("- Only ask the user for credentials NOT in the vault.\n\n");

        sb.append("## Persistent Memory\n");
        sb.append("Facts survive across conversations and load as 'User Preferences' at task start.\n");
        sb.append("When user says 'remember this' or gives standing instructions, ALWAYS store — don't just acknowledge.\n\n");

        // Output format
        sb.append("## Output Format\n");
        sb.append("Respond with a single JSON object:\n");
        sb.append("{\"reasoning\": \"...\", \"tool\": \"tool_name\", \"params\": {\"param1\": \"value1\"}}\n\n");

        // Behavioral guidelines
        sb.append("## Guidelines\n");
        sb.append("- Answer directly with 'respond' if no tools needed.\n");
        sb.append("- On failure, analyze the error and try a different approach. NEVER give up after one failure — try 2-3 alternatives.\n");
        sb.append("- On skill errors: inspect with skill_manage(action='read'), then fix with skill_create using the SAME name (overwrites in-place). NEVER create _v2/_fixed variants.\n");
        sb.append("- Minimize tool calls. Never fabricate outputs or assume success without observing results.\n");
        sb.append("- Explore thoroughly: follow links, check sub-pages, look for embedded resources before saying 'not found'.\n");
        sb.append("- Verify results make sense. If output is garbled/empty/short, fix the tool — don't present broken data.\n");
        sb.append("- Partial data (wrong day/section)? Inspect and fix the skill — pages often have hidden/tabbed content.\n");

        // Language awareness
        sb.append("\n## Language & Locale\n");
        sb.append("- Detect and respond in the user's language.\n");
        sb.append("- Use search terms and selectors in the TARGET content's language, not English.\n");
        sb.append("- Never assume content is English — check first.\n");

        // Self-improvement guidelines
        sb.append("\n## Self-Improvement\n");
        sb.append("All tools in 'Available Tools' are editable Python skills you built.\n");
        sb.append("- No suitable tool? Create one with skill_create. Prefer reusable, general-purpose tools.\n");
        sb.append("- Poor results? Read the code (skill_manage action='read'), then overwrite with skill_create using the SAME name.\n");
        sb.append("- NEVER create variant names like skill_v2, skill_fixed, skill_new — always reuse the original name.\n");
        sb.append("- After multiple failures, reconsider: is the approach fundamentally wrong?\n");
        sb.append("- Remote content skills must handle: encoding, content types, large content, errors.\n");
        sb.append("- Structured content skills: extract readable text, strip markup/boilerplate, preserve structure.\n");

        sb.append("\n## Data Quality\n");
        sb.append("Tool outputs enter your context — they must be clean.\n");
        sb.append("- Extract text, never return raw HTML/XML/binary. Strip boilerplate.\n");
        sb.append("- Garbled text = wrong encoding — fix the tool.\n");
        sb.append("- Content behind links or in files (PDF, DOCX, CSV): fetch and extract, don't just report the link.\n");

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

        // Bootstrapping: when no tools exist, direct the LLM to create them
        if (manifest.isBlank()) {
            sb.append("\n## No Tools Available\n");
            sb.append("Use 'skill_create' as your FIRST action to build the capability you need.\n");
            sb.append("Do NOT call skill_manage (inventory is empty). Do NOT ask the user for permission.\n");
        }
        sb.append("\n");

        // Dynamic vault contents
        List<String> vaultKeys = context.credentialKeys();
        if (!vaultKeys.isEmpty()) {
            sb.append("Vault contains: ").append(String.join(", ", vaultKeys)).append("\n\n");
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
        sb.append("delegate(goal, steps[{description,tool,params}], [checkpoints], [max_steps]) — delegate multi-tool execution to local LLM\n\n");

        // Credential reminder in compact prompt
        List<String> vaultKeys = context.credentialKeys();
        if (!vaultKeys.isEmpty()) {
            sb.append("Vault contains: ").append(String.join(", ", vaultKeys)).append("\n");
        }
        sb.append("Credentials marked ✓ (or listed in vault) are auto-injected — NEVER ask the user for them. Only ask for missing ones.\n");
        sb.append("When creating skills, declare credentials in 'credentials' param (use exact vault key names) — never as tool parameters.\n\n");

        // Output format (always needed)
        sb.append("Output: {\"reasoning\": \"...\", \"tool\": \"name\", \"params\": {...}}\n");

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
