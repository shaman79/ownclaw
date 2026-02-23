package com.ownclaw.skillrunner;

import java.util.Map;

/**
 * Rich failure context captured when a skill execution fails.
 * Carries everything the Mentor needs to diagnose and repair the skill.
 */
public record SkillFailureContext(
        String skillName,
        int version,
        String scriptSource,          // full Python source code
        Map<String, Object> params,   // the params that were sent
        String stdout,                // raw stdout from sandbox
        String stderr,                // raw stderr from sandbox
        int exitCode,
        long durationMs,
        boolean timedOut,
        String parsedOutput,          // what SkillOutputParser extracted (or null)
        String errorSummary           // human-readable summary of what went wrong
) {

    /**
     * Build a compact diagnostic report for the Mentor LLM.
     */
    public String toDiagnosticReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("SKILL FAILURE REPORT\n");
        sb.append("====================\n");
        sb.append("Skill: ").append(skillName).append(" v").append(version).append("\n");
        sb.append("Exit code: ").append(exitCode).append("\n");
        sb.append("Duration: ").append(durationMs).append("ms\n");
        if (timedOut) sb.append("** TIMED OUT **\n");
        sb.append("\n");

        sb.append("PARAMETERS SENT:\n");
        sb.append(truncate(String.valueOf(params), 500)).append("\n\n");

        sb.append("ERROR SUMMARY:\n");
        sb.append(errorSummary != null ? errorSummary : "unknown").append("\n\n");

        if (stderr != null && !stderr.isBlank()) {
            sb.append("STDERR:\n");
            sb.append(truncate(stderr, 2000)).append("\n\n");
        }

        if (stdout != null && !stdout.isBlank()) {
            sb.append("STDOUT (first 1000 chars):\n");
            sb.append(truncate(stdout, 1000)).append("\n\n");
        }

        sb.append("SKILL SOURCE CODE:\n");
        sb.append("```python\n");
        sb.append(truncate(scriptSource, 8000));
        sb.append("\n```\n");

        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "\n... [truncated]" : s;
    }
}
