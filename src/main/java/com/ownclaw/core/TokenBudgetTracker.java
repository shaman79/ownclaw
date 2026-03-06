package com.ownclaw.core;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Tracks cloud LLM token usage per user and enforces budget limits.
 * Records usage in the token_usage table (daily aggregates per provider).
 * Emits warnings at configurable threshold and blocks when budget exhausted.
 */
@Service
public class TokenBudgetTracker {

    private static final Logger log = LoggerFactory.getLogger(TokenBudgetTracker.class);

    private final JdbcTemplate jdbc;
    private final OwnClawConfig config;
    private final ChatStatusEmitter statusEmitter;

    public TokenBudgetTracker(JdbcTemplate jdbc, OwnClawConfig config,
                              ChatStatusEmitter statusEmitter) {
        this.jdbc = jdbc;
        this.config = config;
        this.statusEmitter = statusEmitter;
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

        // Check if approaching or exceeding budget
        checkBudget(userId, tokens);
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
     * Check if a user has budget remaining for a cloud LLM call.
     *
     * @return true if the user can make the call, false if budget exhausted
     */
    public boolean hasBudget(String userId) {
        long limit = config.getBudgets().getDailyCloudTokens();
        if (limit <= 0) return true; // no limit configured
        long used = getTodayUsage(userId);
        return used < limit;
    }

    /**
     * Check if a user has budget for a per-task call.
     *
     * @param estimatedTokens estimated tokens the call will consume
     * @return true if the call fits within per-task and daily budgets
     */
    public boolean canAfford(String userId, int estimatedTokens) {
        long dailyLimit = config.getBudgets().getDailyCloudTokens();
        int perTaskLimit = config.getBudgets().getPerTaskCloudTokens();
        if (dailyLimit <= 0 && perTaskLimit <= 0) return true; // no limits configured
        if (dailyLimit > 0) {
            long dailyUsed = getTodayUsage(userId);
            if (dailyUsed + estimatedTokens > dailyLimit) return false;
        }
        return perTaskLimit <= 0 || estimatedTokens <= perTaskLimit;
    }

    /**
     * Get a usage summary string for display.
     */
    public String getUsageSummary(String userId) {
        long used = getTodayUsage(userId);
        long limit = config.getBudgets().getDailyCloudTokens();
        if (limit <= 0) {
            return String.format("Today: %,d tokens (no limit)", used);
        }
        double pct = (used * 100.0 / limit);
        return String.format("Today: %,d / %,d tokens (%.1f%%)",
                used, limit, pct);
    }

    private void checkBudget(String userId, int justUsed) {
        long used = getTodayUsage(userId);
        long limit = config.getBudgets().getDailyCloudTokens();

        if (limit <= 0) return; // no limit configured

        double ratio = (double) used / limit;
        double warningThreshold = config.getBudgets().getWarningThreshold();

        if (ratio >= 1.0) {
            log.warn("Token budget EXHAUSTED for user {}: {} / {}", userId, used, limit);
            statusEmitter.emit(userId, StatusMessage.Type.WARNING,
                    "Daily cloud token budget exhausted (" + used + " / " + limit + "). "
                            + "Tasks requiring cloud AI will be blocked until tomorrow.");
        } else if (ratio >= warningThreshold) {
            // Only warn once per threshold crossing (check if this is the crossing point)
            long prevUsed = used - justUsed;
            double prevRatio = (double) prevUsed / limit;
            if (prevRatio < warningThreshold) {
                log.info("Token budget warning for user {}: {:.0f}% used", userId, ratio * 100);
                statusEmitter.emit(userId, StatusMessage.Type.WARNING,
                        String.format("Cloud token usage at %.0f%% of daily budget (%,d / %,d)",
                                ratio * 100, used, limit));
            }
        }
    }
}
