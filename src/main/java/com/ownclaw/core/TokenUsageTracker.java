package com.ownclaw.core;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * What each user's cloud calls used, day by day: the tokens billed, the requests made and what
 * they cost, kept in the token_usage table (one row a day for each provider), and the day's
 * totals for whoever shows them.
 * <p>
 * It records and reports, and limits nothing. It used to carry a daily token budget as well
 * ({@code ownclaw.budgets}), which warned that tasks "will be blocked until tomorrow" while no
 * call ever asked it whether to go ahead. The owner's decision is that there are no token
 * budgets, so the limit that limited nothing is gone and the accounting stays.
 */
@Service
public class TokenUsageTracker {

    private final JdbcTemplate jdbc;

    public TokenUsageTracker(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Record token usage for a cloud provider call.
     *
     * @param userId    user who triggered the call
     * @param provider  provider name (openai, anthropic, etc.)
     * @param tokens    total tokens used (prompt + completion)
     * @param costUsd   estimated cost in USD (0.0 if unknown)
     */
    public void recordUsage(String userId, String provider, int tokens, double costUsd) {
        jdbc.update("""
                INSERT INTO token_usage (user_id, date, provider, tokens_used, requests, cost_usd)
                VALUES (?, date('now'), ?, ?, 1, ?)
                ON CONFLICT(user_id, date, provider) DO UPDATE SET
                    tokens_used = tokens_used + excluded.tokens_used,
                    requests = requests + 1,
                    cost_usd = cost_usd + excluded.cost_usd,
                    updated_at = datetime('now')
                """, userId, provider, tokens, costUsd);
    }

    /**
     * Get total cloud tokens used today by a user (across all providers).
     */
    public long getTodayUsage(String userId) {
        Long total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(tokens_used), 0) FROM token_usage WHERE user_id = ? AND date = date('now')",
                Long.class, userId);
        return total != null ? total : 0;
    }

    /**
     * Get total estimated cost today for a user.
     */
    public double getTodayCost(String userId) {
        Double total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(cost_usd), 0.0) FROM token_usage WHERE user_id = ? AND date = date('now')",
                Double.class, userId);
        return total != null ? total : 0.0;
    }

    /**
     * Get a usage summary string for display.
     */
    public String getUsageSummary(String userId) {
        return String.format("Today: %,d tokens", getTodayUsage(userId));
    }
}
