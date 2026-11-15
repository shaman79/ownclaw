package com.ownclaw.core;

import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.interfaces.CommandHandler;
import com.ownclaw.observability.EventLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The cloud's use is counted and shown, and that is all: there are no token budgets, so however
 * much a day used, the commands say the numbers and nothing about a limit. (That each model call
 * of a task reaches the count, priced, is TokenAccountingTest's.)
 */
class TokenUsageTrackerTest {

    @Test
    @DisplayName("each call's tokens and cost are added to the day's row of its provider, and a day's totals are one account's own")
    void recordedPerDayAndProvider(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var usage = new TokenUsageTracker(jdbc);

        usage.recordUsage("u1", "anthropic", 12_450, 0.25);
        usage.recordUsage("u1", "anthropic", 550, 0.05);
        usage.recordUsage("u1", "openai", 2_000, 0.10);
        usage.recordUsage("u2", "anthropic", 7, 0.01);

        // The rows the ops API lists (forensics' tokenUsage): one a day for each provider.
        var rows = jdbc.queryForList("SELECT provider, tokens_used, requests, cost_usd FROM token_usage "
                + "WHERE user_id = 'u1' AND date = date('now') ORDER BY provider");
        assertEquals(2, rows.size(), rows.toString());
        assertEquals("anthropic", rows.get(0).get("provider"));
        assertEquals(13_000, rows.get(0).get("tokens_used"), "both calls");
        assertEquals(2, rows.get(0).get("requests"));
        assertEquals(0.30, ((Number) rows.get(0).get("cost_usd")).doubleValue(), 1e-9);
        assertEquals("openai", rows.get(1).get("provider"));
        assertEquals(2_000, rows.get(1).get("tokens_used"));
        assertEquals(1, rows.get(1).get("requests"));

        assertEquals(15_000, usage.getTodayUsage("u1"), "every provider's tokens of the day");
        assertEquals(0.40, usage.getTodayCost("u1"), 1e-9);
        assertEquals(7, usage.getTodayUsage("u2"), "another account's use is its own");
        assertEquals(0, usage.getTodayUsage("u3"), "and none is none");
        assertEquals(0.0, usage.getTodayCost("u3"));
        // Mutation: recordUsage writes nothing -> no row, and every total is 0.
    }

    @Test
    @DisplayName("/tokens and /log tokens show the day's use and name no budget or limit, however much was used")
    void shownByTheCommands(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        var usage = new TokenUsageTracker(jdbc);
        var events = new EventLogService(jdbc);
        var commands = new CommandHandler(null, null, events, usage, null, null, null, null, null, null,
                null, null, null, null, null, null);

        // Three billion tokens in a day: more than any budget this ever had would have allowed.
        usage.recordUsage("u1", "anthropic", 1_500_000_000, 4_000.0);
        usage.recordUsage("u1", "anthropic", 1_500_000_000, 4_000.0);
        events.log("u1", "t1", "task_completed", "info", "done",
                "{\"cloudTokens\":2500000,\"localTokens\":40000}", 2_540_000);

        // The numbers as the commands group them, in whatever locale the tests run in.
        String tokens = commands.handle("u1", "/tokens").orElseThrow();
        assertEquals(String.format("Today: %,d tokens\nCloud: %,d  |  Local: %,d", 3_000_000_000L, 2_500_000, 40_000),
                tokens);
        String log = commands.handle("u1", "/log tokens").orElseThrow();
        assertEquals(String.format("Token usage today:\n  Cloud: %,d  |  Local: %,d  |  Total: %,d\n  Tasks: 1",
                2_500_000, 40_000, 2_540_000), log);
        for (String reply : new String[] {tokens, log}) {
            assertFalse(reply.toLowerCase().contains("budget") || reply.toLowerCase().contains("limit"), reply);
        }
        // Mutation: /tokens without the tracker's line -> the day's count is shown by no command.
    }
}
