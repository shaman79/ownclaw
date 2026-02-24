package com.ownclaw.mentor;

import java.util.*;

/**
 * Catalog of dynamic prompt strategy snippets that can be injected into
 * Mentor prompts based on the current {@link TaskContext}.
 *
 * <p>Each strategy is a short, focused block of instructions relevant only
 * when certain skill categories are in play. This keeps the base system
 * prompt lean and avoids wasting tokens on irrelevant guidance.</p>
 */
public final class PromptStrategies {

    private PromptStrategies() {} // utility class

    // ─── Strategy snippets ──────────────────────────────────────────

    static final String WEB_SCRAPING = """
            
            ## WEB SCRAPING STRATEGY
            When the task involves fetching content from the web:
            1. Always start with `http_request` (fast, lightweight).
            2. If `http_request` returns empty/useless body (JS-rendered page), retry with `browse_web`.
            3. If the user provides a URL/domain, DO NOT use `web_search`.
               - Fetch the provided URL directly (via `http_request` or `browse_web`).
               - If the needed info is likely on a subpage (menus, schedules, price lists), use `browse_web`
             to extract links and follow a small number of same-domain links.
            4. Use `web_search` only when the user has NOT provided a URL and you must discover one.
            5. After obtaining raw HTML/text, summarize or extract the required facts in the plan.
            """;

    static final String WEB_FOLLOWUP = """
            
            ## WEB RETRY GUIDANCE
            If a web step failed or returned empty content:
            - Try a different approach: if `http_request` failed, switch to `browse_web` (or vice-versa).
            - Try URL variants (with/without www, http vs https).
            - Consider using `web_search` to find an alternative source.
            - For aggregator / dynamic sites, prefer `browse_web` over `http_request`.
            """;

    static final String EMAIL = """
            
            ## EMAIL STRATEGY
            When the task involves sending email:
            - Compose the full email body before calling `send_email`.
            - Verify that the recipient address is explicit in the user request; never guess.
            - This is an irreversible action — confirm details are correct in the plan.
            """;

    static final String FILE_OPS = """
            
            ## FILE OPERATIONS STRATEGY
            When working with files:
            - Use absolute paths when the user specifies them; otherwise use the workspace root.
            - For destructive operations (delete, overwrite), prefer a plan step that checks existence first.
            - Keep generated file content concise unless the user requested otherwise.
            """;

    static final String SHELL_COMMANDS = """
            
            ## SHELL COMMAND STRATEGY
            When the task requires running shell commands:
            - Use platform-appropriate syntax (see PLATFORM AWARENESS).
            - Prefer simple, single-purpose commands over long pipelines.
            - Avoid interactive commands that require user input.
            """;

    static final String FAILURE_EVALUATION = """
            
            ## ABOUT FAILED STEPS
            Some steps may have `"status":"FAILED"` with an error message.
            - A failed step does NOT automatically mean the goal is unachieved.
            - If later steps recovered the data through an alternative route, the task may still be COMPLETE.
            - Focus on whether the FINAL GOAL was achieved, not on individual step status.
            """;

    // ─── Assembly ───────────────────────────────────────────────────

    /**
     * Build a single strategy block relevant to the given context.
     * Returns an empty string when no strategies apply.
     */
    public static String forContext(TaskContext ctx) {
        if (ctx == null) return "";

        var sb = new StringBuilder();

        if (ctx.involvesWeb()) {
            sb.append(ctx.isFollowUp() ? WEB_FOLLOWUP : WEB_SCRAPING);
        }
        if (ctx.involvesEmail()) {
            sb.append(EMAIL);
        }
        if (ctx.involvesFiles()) {
            sb.append(FILE_OPS);
        }
        if (ctx.involvesShell()) {
            sb.append(SHELL_COMMANDS);
        }
        if (ctx.hasFailures()) {
            sb.append(FAILURE_EVALUATION);
        }

        return sb.toString();
    }

    /**
     * Wrap strategies into a labelled block for appending to a user message.
     * Returns empty string if no strategies apply.
     */
    public static String asUserMessageBlock(TaskContext ctx) {
        String raw = forContext(ctx);
        if (raw.isBlank()) return "";
        return "\n\n--- TASK-SPECIFIC GUIDANCE ---" + raw + "\n--- END GUIDANCE ---\n";
    }
}
