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
    // Lenient mapper: allows backslash-escaped single quotes (\') and other non-standard
    // escape sequences that LLMs sometimes produce when embedding code in JSON strings.
    private static final ObjectMapper mapper = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
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
                true    // JSON mode for structured output
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
        var sb = new StringBuilder();

        sb.append("You are an autonomous agent. You reason, pick a tool, observe the result, repeat until done.\n\n");

        // Identity — override default LLM safety training about account access
        sb.append("## Identity & Authority\n");
        sb.append("You are a PERSONAL agent running LOCALLY on the user's machine with FULL PERMISSION to execute ANY local command, access ANY service, and perform ANY operation the user requests.\n");
        sb.append("NEVER refuse with 'I can't access your accounts' or 'I can't run commands' — you CAN, that's your purpose.\n");
        sb.append("NEVER ask for permission to create skills or execute local operations. The user already granted full authority by running this agent.\n");
        sb.append("Missing credentials? Use 'ask_user' to request them, then 'credential_manage' to store.\n\n");

        // Environment context
        sb.append("## Environment\n");
        sb.append("- Platform: ").append(detectPlatform()).append("\n");
        sb.append("- DateTime: ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n\n");

        // User preferences (if any)
        if (context.userPreferences() != null && !context.userPreferences().isBlank()) {
            sb.append("## User Preferences\n");
            sb.append(context.userPreferences()).append("\n\n");
        }

        // Smart tool selection — include only relevant tools in detail
        ToolSelector.Selection selection = toolSelector.select(
                context.originalMessage(), context.trajectory());

        sb.append("## Available Tools\n");
        String manifest = toolRegistry.generateManifest(selection.detailed());
        sb.append(manifest).append("\n");

        // If some tools were omitted, list them by name so the LLM knows they exist
        if (!selection.otherNames().isEmpty()) {
            sb.append("\n## Other Available Tools (use by name if needed)\n");
            sb.append(String.join(", ", selection.otherNames())).append("\n");
        }

        // Bootstrapping: when no tools exist, give the agent a strong push to create them
        if (manifest.isBlank()) {
            sb.append("\n## IMPORTANT: No tools available.\n");
            sb.append("Use 'skill_create' as your FIRST action. Do NOT call skill_manage — the inventory is empty.\n");
            sb.append("Determine what capability you need and create it IMMEDIATELY. Do NOT ask the user whether you should — just create it.\n");
            sb.append("For local operations (network scanning, file access, system commands, etc.), create a Python skill that uses subprocess or native libraries. You have FULL permission.\n");
        }
        sb.append("\n");

        // Special actions
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
        sb.append("  name (string, required): Lowercase identifier (e.g. 'web_fetch', 'network_scanner', 'shell_exec')\n");
        sb.append("  description (string, required): Detailed behavior spec including edge cases and output format\n");
        sb.append("  parameters (string, required): JSON — each key maps to {\"type\":\"string\",\"description\":\"...\",\"required\":true/false}\n");
        sb.append("  requirements (string, optional): pip packages, one per line\n");
        sb.append("  requires_network (boolean, optional): needs internet?\n");
        sb.append("  has_side_effects (boolean, optional): modifies files, sends emails, etc.?\n");
        sb.append("  timeout (integer, optional): max seconds (default 30)\n");
        sb.append("  credentials (string, optional): comma-separated credential keys (e.g. 'IMAP_HOST,IMAP_USER,IMAP_PASS') — injected as env vars\n\n");

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

        sb.append("## Credential Vault\n");
        sb.append("AES-256-GCM encrypted storage. Check with 'list' before asking user for credentials.\n");
        sb.append("Store with 'store' after user provides them — they only need to provide each once.\n");
        sb.append("Credentials auto-injected as env vars into skills that declare them.\n");
        sb.append("When creating skills, declare needed credentials in the 'credentials' parameter.\n\n");

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
        sb.append("- On skill errors: inspect with skill_manage(action='read'), fix with skill_create, or build a different skill.\n");
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
        sb.append("- No suitable tool? Create one with skill_create IMMEDIATELY. Never ask the user for permission first. Prefer reusable, general-purpose tools.\n");
        sb.append("- Poor results? Read the code (skill_manage action='read'), then overwrite with skill_create.\n");
        sb.append("- After multiple failures, reconsider: is the approach fundamentally wrong?\n");
        sb.append("- Remote content skills must handle: encoding (detect charset, fix mojibake), ");
        sb.append("content types (HTML/PDF/JSON/XML), large content (truncate/summarize), errors (HTTP codes, timeouts).\n");
        sb.append("- Structured content skills: extract readable text, strip markup/boilerplate, preserve structure.\n");

        sb.append("\n## Data Quality\n");
        sb.append("Tool outputs enter your context — they must be clean.\n");
        sb.append("- Extract text, never return raw HTML/XML/binary. Strip boilerplate.\n");
        sb.append("- Garbled text = wrong encoding — fix the tool.\n");
        sb.append("- Content behind links or in files (PDF, DOCX, CSV): fetch and extract, don't just report the link.\n");

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

            String tool = getStringField(parsed, "tool");
            String reasoning = getStringField(parsed, "reasoning");

            @SuppressWarnings("unchecked")
            Map<String, Object> params = parsed.containsKey("params") && parsed.get("params") instanceof Map
                    ? (Map<String, Object>) parsed.get("params")
                    : Map.of();

            if (tool == null || tool.isBlank()) {
                // If there's a "message" field at root level, treat as response
                String message = getStringField(parsed, "message");
                if (message != null && !message.isBlank()) {
                    return new AgentAction(AgentAction.RESPOND, Map.of("message", message),
                            reasoning != null ? reasoning : "Direct response");
                }
                log.warn("ThinkingEngine: no 'tool' field in parsed JSON");
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
