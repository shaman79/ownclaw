package com.ownclaw.mentor;

import java.util.*;

/**
 * Lightweight context object that carries task-specific metadata
 * to guide dynamic prompt construction. Passed from TaskOrchestrator
 * to MentorService so that only relevant strategy sections are injected.
 *
 * <p>This avoids bloating every Mentor prompt with domain-specific instructions
 * (web scraping, email, file ops, etc.) that don't apply to the current task.</p>
 */
public record TaskContext(
        Set<String> matchedSkills,
        boolean hasFailures,
        boolean isFollowUp
) {

    /** Empty context — no skills matched, no special strategies injected. */
    public static final TaskContext EMPTY = new TaskContext(Set.of(), false, false);

    /**
     * Build a TaskContext from a classification's matched-skills JSON.
     *
     * @param matchesJson JSON array string like [{"name":"http_request","confidence":0.9}]
     * @return context with extracted skill names
     */
    public static TaskContext fromMatchesJson(String matchesJson) {
        Set<String> skills = new LinkedHashSet<>();
        if (matchesJson != null && !matchesJson.isBlank()) {
            // Lightweight extraction — avoid full Jackson parse for a simple array
            var matcher = java.util.regex.Pattern
                    .compile("\"name\"\\s*:\\s*\"([^\"]+)\"")
                    .matcher(matchesJson);
            while (matcher.find()) {
                skills.add(matcher.group(1));
            }
        }
        return new TaskContext(skills, false, false);
    }

    /** Return a copy with failures flag set. */
    public TaskContext withFailures() {
        return new TaskContext(matchedSkills, true, isFollowUp);
    }

    /** Return a copy marked as a follow-up round. */
    public TaskContext asFollowUp() {
        return new TaskContext(matchedSkills, hasFailures, true);
    }

    // ─── skill-group predicates ───

    public boolean involvesWeb() {
        return matchedSkills.stream().anyMatch(s ->
                s.contains("http") || s.contains("browse") || s.contains("web")
                        || s.contains("scrape") || s.contains("fetch") || s.contains("crawl"));
    }

    public boolean involvesEmail() {
        return matchedSkills.stream().anyMatch(s ->
                s.contains("email") || s.contains("mail") || s.contains("smtp"));
    }

    public boolean involvesFiles() {
        return matchedSkills.stream().anyMatch(s ->
                s.contains("file") || s.contains("directory") || s.contains("folder"));
    }

    public boolean involvesShell() {
        return matchedSkills.stream().anyMatch(s ->
                s.contains("shell") || s.contains("command"));
    }
}
