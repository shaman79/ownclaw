package com.ownclaw.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.llm.*;
import com.ownclaw.skills.SkillManifest;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The Executor — local LLM agent that classifies tasks, matches skills,
 * compresses context, and manages conversation.
 * All calls go through the OllamaSemaphore to serialize GPU access.
 */
@Service
public class ExecutorService {

    private static final Logger log = LoggerFactory.getLogger(ExecutorService.class);

    private final OllamaProvider ollama;
    private final OllamaSemaphore semaphore;
    private final SkillManifest manifest;
    private final ObjectMapper mapper;

    private static String platformInfo() {
        String os = System.getProperty("os.name", "Unknown");
        String shell = os.toLowerCase().contains("win") ? "PowerShell" : "Bash";
        String dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss (EEEE)"));
        return "Platform: " + os + " | Shell: " + shell + " | Current date/time: " + dateTime;
    }

    public ExecutorService(OllamaProvider ollama, OllamaSemaphore semaphore,
                           SkillManifest manifest, ObjectMapper mapper) {
        this.ollama = ollama;
        this.semaphore = semaphore;
        this.manifest = manifest;
        this.mapper = mapper;
    }

    /**
     * Classify a user message: determine intent, match skills, produce confidence score.
     *
     * @param userMessage raw user input
     * @return classification result with matched skills and confidence
     */
    public ClassificationResult classify(String userMessage) {
        String skillSnippet = manifest.toPromptSnippet();

        String systemPrompt = """
            You are a strict task classifier. Respond with ONLY a JSON object, no other text.
            
            SYSTEM INFO: %s
            
            AVAILABLE SKILLS:
            %s
            
            JSON format:
            {"intent":"...","matches":[{"name":"skill_name","confidence":0.0-1.0}],"overall_confidence":0.0-1.0,"needs_mentor":true/false,"conversational":true/false}
            
            "conversational" = true ONLY for pure greetings, chitchat, questions about the system
            itself with zero actionable intent, or when the answer is already in SYSTEM INFO.
            Default to false — any actionable request (fetch, run, write, search, check, open, go to)
            is NOT conversational. When in doubt, set conversational = false.
            "needs_mentor" = true if task needs multiple steps or skills.
            """.formatted(platformInfo(), skillSnippet);

        List<LlmMessage> messages = List.of(
                LlmMessage.system(systemPrompt),
                LlmMessage.user(userMessage)
        );

        semaphore.acquire();
        try {
            LlmResponse response = ollama.chat(messages, LlmRequestConfig.withMaxTokens(1024));
            return parseClassification(response.content(), response);
        } finally {
            semaphore.release();
        }
    }

    /**
     * Compress a user message and context into a compact payload for the Mentor.
     *
     * @param userMessage   original user message
     * @param context       conversation context (last N messages summary)
     * @param classification   the classification result
     * @return compressed payload string for Mentor consumption
     */
    public String compressForMentor(String userMessage, String context, ClassificationResult classification) {
        // Short messages don't need compression — pass through directly to avoid
        // the local LLM hallucinating/losing critical literals (URLs, paths, etc.)
        if (userMessage.length() < 500 && (context == null || context.length() < 200)) {
            log.debug("Message short enough, skipping compression ({}chars)", userMessage.length());
            return buildDirectPayload(userMessage, context, classification);
        }

        String systemPrompt = """
            Compress the following task + context into a minimal payload for a planning LLM.
            Keep only essential information. Remove pleasantries, filler, redundancy.
            Output a single JSON object with: "task", "skills_matched", "user_context", "credentials_needed".
            
            CRITICAL: You MUST preserve ALL literal values EXACTLY as given:
            - URLs (http/https links)
            - File paths
            - Proper names, domain names
            - Numbers, dates, codes
            - Quoted strings
            Never paraphrase, shorten, or replace these literals with examples or descriptions.
            Be concise otherwise — every token costs money.
            """;

        String userContent = "TASK: " + userMessage
                + "\nCONTEXT: " + (context != null ? context : "none")
                + "\nSKILLS MATCHED: " + classification.matchesJson();

        List<LlmMessage> messages = List.of(
                LlmMessage.system(systemPrompt),
                LlmMessage.user(userContent)
        );

        semaphore.acquire();
        try {
            LlmResponse response = ollama.chat(messages, LlmRequestConfig.withMaxTokens(512));
            return response.content();
        } finally {
            semaphore.release();
        }
    }

    /**
     * Build a direct (uncompressed) payload for the Mentor when the message is short.
     */
    private String buildDirectPayload(String userMessage, String context,
                                       ClassificationResult classification) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"task\":").append(escapeJsonString(userMessage));
        sb.append(",\"skills_matched\":").append(classification.matchesJson());
        if (context != null && !context.isBlank()) {
            sb.append(",\"user_context\":").append(escapeJsonString(context));
        } else {
            sb.append(",\"user_context\":\"none\"");
        }
        sb.append(",\"credentials_needed\":[]}");
        return sb.toString();
    }

    private String escapeJsonString(String s) {
        try {
            return mapper.writeValueAsString(s);
        } catch (Exception e) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }

    /**
     * Compress execution results into a concise summary for Mentor review.
     */
    public String compressResults(String planSummary, String rawResults) {
        String systemPrompt = """
            Compress these execution results into a minimal summary for review.
            Include: what succeeded, what failed, key output values. Be extremely concise.
            Output plain text, max 200 words.
            """;

        List<LlmMessage> messages = List.of(
                LlmMessage.system(systemPrompt),
                LlmMessage.user("PLAN: " + planSummary + "\nRESULTS: " + rawResults)
        );

        semaphore.acquire();
        try {
            LlmResponse response = ollama.chat(messages, LlmRequestConfig.withMaxTokens(256));
            return response.content();
        } finally {
            semaphore.release();
        }
    }

    private ClassificationResult parseClassification(String json, LlmResponse response) {
        try {
            json = LlmOutputUtils.stripCodeFences(json);

            // Try parsing as-is, then attempt repair if truncated
            JsonNode node = tryParseJson(json);
            if (node == null) {
                node = tryParseJson(repairTruncatedJson(json));
            }
            if (node == null) {
                log.warn("Classification JSON unparseable even after repair");
                return new ClassificationResult("unknown", 0.2, true, false, "[]",
                        response.promptTokens(), response.completionTokens());
            }

            String intent = node.path("intent").asText("unknown");
            double confidence = node.path("overall_confidence").asDouble(0.5);
            boolean needsMentor = node.path("needs_mentor").asBoolean(true);
            boolean conversational = node.path("conversational").asBoolean(false);
            String matchesJson = node.has("matches") ? node.path("matches").toString() : "[]";

            return new ClassificationResult(intent, confidence, needsMentor,
                    conversational, matchesJson, response.promptTokens(), response.completionTokens());
        } catch (Exception e) {
            log.warn("Failed to parse classification JSON, defaulting to low confidence: {}", e.getMessage());
            return new ClassificationResult("unknown", 0.2, true, false, "[]",
                    response.promptTokens(), response.completionTokens());
        }
    }

    /**
     * Generate a conversational response using the local LLM.
     * Includes recent conversation history for context continuity.
     *
     * @param userMessage user input
     * @param history     recent messages (oldest first): list of {role, content}
     * @return LLM-generated conversational response
     */
    public String converse(String userMessage, List<LlmMessage> history) {
        String systemPrompt = """
            You are OwnClaw, a helpful autonomous AI assistant.
            SYSTEM INFO: %s
            Respond naturally and helpfully. Answer date/time questions from SYSTEM INFO.
            Keep responses under 200 words unless detail is needed. Use markdown.
            """.formatted(platformInfo());

        List<LlmMessage> messages = new java.util.ArrayList<>();
        messages.add(LlmMessage.system(systemPrompt));
        // Add conversation history for context
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(LlmMessage.user(userMessage));

        semaphore.acquire();
        try {
            LlmResponse response = ollama.chat(messages, LlmRequestConfig.withMaxTokens(512));
            return response.content();
        } finally {
            semaphore.release();
        }
    }

    /**
     * Summarize raw task execution results into a human-readable response.
     * Extracts relevant info from JSON/HTML outputs and formats for the user.
     *
     * @param userMessage original user request
     * @param rawOutput   raw output from skill execution
     * @return user-friendly summary
     */
    public String summarize(String userMessage, String rawOutput) {
        // Pre-process: strip HTML tags to extract meaningful text
        String processed = preprocessForSummarization(rawOutput);

        // Truncate processed output to avoid overwhelming the LLM
        String truncated = processed.length() > 8000
                ? processed.substring(0, 8000) + "\n... [truncated]"
                : processed;

        log.info("Summarizer input: raw={}ch, processed={}ch, truncated={}ch",
                rawOutput.length(), processed.length(), truncated.length());

        String systemPrompt = """
            You are a data extraction assistant. A task was executed on behalf of the user.
            Extract and present ONLY the relevant information from the output below.
            
            STRICT RULES:
            - ONLY output information that is LITERALLY present in the RAW OUTPUT.
            - NEVER invent, fabricate, guess, or add ANY information not found verbatim in the output.
            - If the output is in a non-English language, preserve the original text — do NOT translate.
            - Use markdown formatting (headers, lists, bold) for readability.
            - Be thorough — include ALL relevant data from the matching section, not just a sample.
            - If you cannot find the requested data in the output, say so honestly.
            - Do NOT show raw JSON or HTML unless the user asked for it.
            """;

        String userContent = "USER ASKED: " + userMessage + "\n\nRAW OUTPUT:\n" + truncated;

        List<LlmMessage> messages = List.of(
                LlmMessage.system(systemPrompt),
                LlmMessage.user(userContent)
        );

        semaphore.acquire();
        try {
            LlmResponse response = ollama.chat(messages, LlmRequestConfig.withMaxTokens(1024));
            String content = response.content();
            log.info("Summarizer output: {}ch, {} prompt + {} completion tokens",
                    content != null ? content.length() : 0,
                    response.promptTokens(), response.completionTokens());
            return content;
        } finally {
            semaphore.release();
        }
    }

    /**
     * Evaluate whether the execution results fully answer the user's question.
     * If not, identifies what's missing and what follow-up actions are needed.
     *
     * <p>Returns a JSON object:
     * <pre>
     * {"complete": true/false, "analysis": "...", "follow_up": "description of what to do next"}
     * </pre>
     *
     * @param userMessage original user request
     * @param rawOutput   raw results from plan execution
     * @return evaluation result
     */
    public CompletenessResult evaluateCompleteness(String userMessage, String rawOutput) {
        // Pre-process to strip HTML, keep links visible
        String processed = preprocessForCompleteness(rawOutput);
        String truncated = processed.length() > 6000
                ? processed.substring(0, 6000) + "\n... [truncated]"
                : processed;

        String systemPrompt = """
            You are an evaluation agent. Decide if the output fully answers the user's question.
            
            SYSTEM INFO: %s
            
            Reply with ONLY this JSON (no other text):
            {"complete": true/false, "analysis": "1-2 sentences max", "follow_up": "action needed or null"}
            
            Rules:
            - complete=true ONLY if the actual requested data is present in the output
            - complete=false if the output references or links to other resources that
              likely contain the answer — put exact URLs or resource identifiers in follow_up
            - Keep analysis SHORT (under 50 words)
            - follow_up must mention specific URLs/actions; set null if complete=true
            
            CRITICAL — what counts as "actual data":
            - An unresolved shell expression like $(date +%%A), `date`, `Get-Date` is NOT actual data.
              It is a command template, not an answer.
            - Error messages like "invalid format", "command not found" are NOT actual data.
            - The output must contain the LITERAL answer (e.g. "Monday", "2026-02-23", etc.)
              not a command that WOULD produce the answer if executed.
            - If the output only shows shell commands or error messages, set complete=false
              and suggest the correct platform command in follow_up.
            """.formatted(platformInfo());

        String userContent = "USER ASKED: " + userMessage + "\n\nEXECUTION OUTPUT:\n" + truncated;

        List<LlmMessage> messages = List.of(
                LlmMessage.system(systemPrompt),
                LlmMessage.user(userContent)
        );

        semaphore.acquire();
        try {
            LlmResponse response = ollama.chat(messages, LlmRequestConfig.withMaxTokens(1024));
            return parseCompletenessResult(response.content());
        } catch (Exception e) {
            log.warn("Completeness evaluation failed: {}", e.getMessage());
            // On failure, assume complete to avoid infinite loops
            return new CompletenessResult(true, "Evaluation failed", null);
        } finally {
            semaphore.release();
        }
    }

    private CompletenessResult parseCompletenessResult(String raw) {
        String json = LlmOutputUtils.stripCodeFences(raw);

        // Try parsing as-is first, then attempt repair if truncated
        JsonNode node = tryParseJson(json);
        if (node == null) {
            node = tryParseJson(repairTruncatedJson(json));
        }
        if (node == null) {
            log.warn("Completeness JSON unparseable even after repair, assuming incomplete");
            // Default to incomplete — better to do an unnecessary follow-up than to miss data
            return new CompletenessResult(false, "LLM returned malformed JSON",
                    "Re-examine the output for any linked resources or missing data");
        }

        boolean complete = node.path("complete").asBoolean(false);
        String analysis = node.path("analysis").asText("");
        String followUp = node.has("follow_up") && !node.get("follow_up").isNull()
                ? node.get("follow_up").asText() : null;
        return new CompletenessResult(complete, analysis, followUp);
    }

    private JsonNode tryParseJson(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Attempt to repair truncated JSON from LLM output.
     * Handles common cases: unclosed strings, missing closing braces.
     */
    private String repairTruncatedJson(String json) {
        if (json == null || json.isBlank()) return "{}";
        String repaired = json.strip();

        // Count unmatched quotes — if odd, close the last string
        long quotes = repaired.chars().filter(c -> c == '"').count();
        if (quotes % 2 != 0) {
            repaired = repaired + "\"";
        }

        // Close unclosed braces/brackets
        int braces = 0, brackets = 0;
        boolean inStr = false;
        for (int i = 0; i < repaired.length(); i++) {
            char c = repaired.charAt(i);
            if (c == '"' && (i == 0 || repaired.charAt(i - 1) != '\\')) inStr = !inStr;
            if (!inStr) {
                if (c == '{') braces++;
                else if (c == '}') braces--;
                else if (c == '[') brackets++;
                else if (c == ']') brackets--;
            }
        }
        repaired = repaired + "]".repeat(Math.max(0, brackets)) + "}".repeat(Math.max(0, braces));

        return repaired;
    }

    /**
     * Pre-process output for completeness evaluation.
     * Strips HTML but preserves link URLs so the evaluator can identify follow-up targets.
     * Handles both single JSON output and multi-step "[Step N — ...]" wrapped format.
     */
    private String preprocessForCompleteness(String raw) {
        return preprocessSteps(raw, this::stripHtmlKeepLinks, null);
    }

    /**
     * Pre-process raw skill output for summarization.
     * Strips HTML tags, normalizes whitespace, and extracts readable text.
     * Handles both single JSON output and the multi-step "[Step N — ...]" wrapped format.
     */
    private String preprocessForSummarization(String raw) {
        return preprocessSteps(raw, this::stripHtml, "Page content:\n");
    }

    /**
     * Shared multi-step / single-block preprocessing.
     * @param raw           the raw skill output
     * @param htmlStripper  function to strip HTML (with or without link preservation)
     * @param prefix        optional prefix prepended before stripped HTML body (e.g. "Page content:\n"), or null
     */
    private String preprocessSteps(String raw, java.util.function.UnaryOperator<String> htmlStripper, String prefix) {
        if (raw == null || raw.isBlank()) return raw;

        if (raw.contains("[Step ")) {
            var sb = new StringBuilder();
            String[] sections = raw.split("(?=\\[Step \\d+)");
            for (String section : sections) {
                String trimmed = section.strip();
                if (trimmed.isEmpty()) continue;

                int newline = trimmed.indexOf('\n');
                if (newline < 0) {
                    sb.append(trimmed).append("\n");
                    continue;
                }

                String header = trimmed.substring(0, newline).strip();
                String content = trimmed.substring(newline + 1).strip().replace("---", "").strip();

                sb.append(header).append("\n");
                if (!content.isEmpty()) {
                    sb.append(preprocessSingleBlock(content, htmlStripper, prefix)).append("\n\n");
                }
            }
            return sb.toString().strip();
        }

        return preprocessSingleBlock(raw, htmlStripper, prefix);
    }

    /**
     * Process a single output block — parse JSON body or strip HTML.
     */
    private String preprocessSingleBlock(String raw, java.util.function.UnaryOperator<String> htmlStripper, String prefix) {
        try {
            String jsonCandidate = raw.strip();
            if (jsonCandidate.startsWith("{")) {
                JsonNode node = mapper.readTree(jsonCandidate);
                if (node.has("body") && node.get("body").isTextual()) {
                    String body = node.get("body").asText();
                    if (body.contains("<") && body.contains(">")) {
                        StringBuilder sb = new StringBuilder();
                        if (node.has("status_code")) {
                            sb.append("HTTP ").append(node.get("status_code").asInt()).append("\n");
                        }
                        if (prefix != null) sb.append(prefix);
                        sb.append(htmlStripper.apply(body));
                        return sb.toString();
                    }
                    return body;
                }
            }
        } catch (Exception ignored) {}

        if (raw.contains("<html") || raw.contains("<body") || raw.contains("<div")) {
            return htmlStripper.apply(raw);
        }
        return raw;
    }

    /**
     * Strip HTML but preserve href URLs so the evaluator can see linked resources.
     * Uses Jsoup for robust parsing.
     */
    private String stripHtmlKeepLinks(String html) {
        Document doc = Jsoup.parse(html);
        // Remove non-visible elements
        doc.select("script, style, noscript, svg, iframe, head").remove();
        // Convert <a> to markdown-style [text](url) before extracting text
        for (Element link : doc.select("a[href]")) {
            String text = link.text();
            String href = link.attr("href");
            if (!text.isBlank() && !href.isBlank()) {
                link.replaceWith(new org.jsoup.nodes.TextNode("[" + text + "](" + href + ")"));
            }
        }
        String text = doc.body() != null ? doc.body().wholeText() : doc.text();
        // Normalize whitespace
        text = text.replaceAll("[ \\t]+", " ");
        text = text.replaceAll("\\n{3,}", "\n\n");
        return text.strip();
    }

    /** Result of completeness evaluation. */
    public record CompletenessResult(boolean complete, String analysis, String followUp) {}

    /**
     * Strip HTML to plain text using Jsoup.
     * Removes scripts, styles, and all tags; decodes entities; normalizes whitespace.
     */
    private String stripHtml(String html) {
        Document doc = Jsoup.parse(html);
        doc.select("script, style, noscript, svg, iframe, head").remove();
        String text = doc.body() != null ? doc.body().text() : doc.text();
        // Normalize whitespace
        text = text.replaceAll("\\n{3,}", "\n\n");
        return text.strip();
    }

    /**
     * Result of task classification by the Executor.
     */
    public record ClassificationResult(
            String intent,
            double confidence,
            boolean needsMentor,
            boolean conversational,
            String matchesJson,
            int promptTokens,
            int completionTokens
    ) {}
}
