package com.ownclaw.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final ObjectMapper mapper = new ObjectMapper();

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

        sb.append("You are an autonomous agent that accomplishes tasks by using tools.\n");
        sb.append("You operate in a loop: you reason about what to do, choose a tool to invoke, ");
        sb.append("observe the result, and repeat until the task is complete.\n\n");

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
        sb.append(toolRegistry.generateManifest(selection.detailed())).append("\n");

        // If some tools were omitted, list them by name so the LLM knows they exist
        if (!selection.otherNames().isEmpty()) {
            sb.append("\n## Other Available Tools (use by name if needed)\n");
            sb.append(String.join(", ", selection.otherNames())).append("\n");
        }
        sb.append("\n");

        // Special actions
        sb.append("## Special Actions\n");
        sb.append("These are always available regardless of what tools exist.\n\n");
        sb.append("respond: Deliver a final answer to the user. Use when the task is complete or ");
        sb.append("you can answer directly without tools.\n");
        sb.append("  message (string, required): The response to show the user\n\n");
        sb.append("ask_user: Ask the user a clarifying question when you need more information.\n");
        sb.append("  message (string, required): The question to ask\n\n");

        sb.append("skill_create: Create or update a Python skill that becomes a new tool.\n");
        sb.append("  name (string, required): Lowercase identifier (letters, digits, underscores). E.g. 'web_fetch'\n");
        sb.append("  description (string, required): Clear one-line description of what the skill does\n");
        sb.append("  code (string, required): Full Python script. Must define `def run(params):` that returns a dict with 'output' key.\n");
        sb.append("  parameters (string, required): JSON object defining input parameters. ");
        sb.append("Each key maps to {\"type\":\"string\",\"description\":\"...\",\"required\":true/false}\n");
        sb.append("  requirements (string, optional): pip requirements, one per line (e.g. 'requests\\nbeautifulsoup4')\n");
        sb.append("  requires_network (boolean, optional): true if the skill needs internet access\n");
        sb.append("  has_side_effects (boolean, optional): true if the skill modifies files, sends emails, etc.\n");
        sb.append("  timeout (integer, optional): Max execution time in seconds (default 30)\n\n");

        sb.append("skill_manage: Read, delete, list or analyze existing skills.\n");
        sb.append("  action (string, required): One of 'read', 'delete', 'list', 'analyze'\n");
        sb.append("  name (string, required for read/delete): The skill name to operate on\n\n");

        // Output format
        sb.append("## Output Format\n");
        sb.append("You MUST respond with a single JSON object:\n");
        sb.append("{\n");
        sb.append("  \"reasoning\": \"your step-by-step thinking about what to do next\",\n");
        sb.append("  \"tool\": \"tool_name\",\n");
        sb.append("  \"params\": { \"param1\": \"value1\" }\n");
        sb.append("}\n\n");

        // Behavioral guidelines
        sb.append("## Guidelines\n");
        sb.append("- Think carefully before each action. Consider what information you have and what you still need.\n");
        sb.append("- If you can answer the user directly from your knowledge, use 'respond' immediately.\n");
        sb.append("- If a tool fails, analyze the error and try a different approach rather than repeating the same action.\n");
        sb.append("- Use the minimum number of tool calls needed. Do not use tools unnecessarily.\n");
        sb.append("- When the task is complete, always use 'respond' to deliver the final answer.\n");
        sb.append("- If you cannot complete the task after reasonable effort, use 'respond' to explain what you tried and why it didn't work.\n");
        sb.append("- Never fabricate tool outputs or assume a tool succeeded without observing the result.\n");

        // Language awareness
        sb.append("\n## Language & Locale Awareness\n");
        sb.append("- Detect the language of the user's message and respond in the same language.\n");
        sb.append("- When working with web pages, documents or data, identify their language from ");
        sb.append("the content and adapt your strategy accordingly.\n");
        sb.append("- Use search keywords, queries, and extraction selectors in the SAME LANGUAGE as the ");
        sb.append("target content. For example, if a web page is in Czech, search for Czech terms, ");
        sb.append("not English translations.\n");
        sb.append("- When results are in a foreign language, translate or explain them for the user ");
        sb.append("based on the language they used in their original request.\n");
        sb.append("- Never assume content is in English. Always check the actual language first.\n");

        // Self-improvement guidelines
        sb.append("\n## Self-Improvement\n");
        sb.append("You build and improve your own tools. All tools in 'Available Tools' are Python skills you can read and edit.\n");
        sb.append("- If no suitable tool exists for a sub-task, create one with skill_create.\n");
        sb.append("- If a tool returns poor or empty results, use skill_manage (action='read') to inspect its code, ");
        sb.append("then use skill_create to overwrite it with an improved version.\n");
        sb.append("- After multiple failures, pause and reconsider: is the current approach fundamentally wrong? ");
        sb.append("Could a different tool or technique succeed where the current one fails?\n");
        sb.append("- Prefer creating reusable, general-purpose tools over single-use scripts.\n");
        sb.append("- When creating tools that fetch content, handle different content types ");
        sb.append("(HTML, PDF, images, APIs) and always report what was actually received.\n");
        sb.append("\n## Critical: Tool Output Quality\n");
        sb.append("Your tool outputs are included in your reasoning context. Follow these rules:\n");
        sb.append("- Tools that fetch structured content (HTML, XML, etc.) should extract readable text, not return raw markup.\n");
        sb.append("- Return only meaningful content — strip boilerplate, navigation, and formatting artifacts.\n");
        sb.append("- If a tool returns too much data, improve it to focus on the relevant section.\n");

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
            sb.append("You have handled similar tasks before. Use these insights if applicable:\n");
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
        sb.append("You have already taken the following actions in this task:\n\n");
        sb.append(trajectory.toPromptSummary());
        sb.append("Based on the above results, decide what to do next. ");
        sb.append("If the task is complete, use 'respond' to deliver the final answer.");
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
