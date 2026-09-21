package com.ownclaw.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * When a task that has stopped making progress gets cancelled.
 * <p>
 * The check that was supposed to do this lived at the top of the agent loop, and could not fire:
 * {@code markProgress()} runs at the end of every branch below it, so the reading it takes is
 * microseconds old. Worse, it was in the wrong place entirely — a task that hangs is hanging
 * <em>inside</em> a step, and while it does, the loop never reaches the top of the next iteration
 * to check anything. A check running on the stuck thread only runs when the thread is not stuck.
 * The watchdog runs on the scheduler instead.
 */
class StallWatchdogTest {

    private static final long TIMEOUT = 600_000L;   // tasks.stall-timeout, 600s

    @Test
    @DisplayName("a task past the timeout is cancelled")
    void stalledIsCancelled() {
        assertTrue(AgentLoop.shouldCancelForStall(TIMEOUT + 1, TIMEOUT, false));
        assertTrue(AgentLoop.shouldCancelForStall(TIMEOUT * 3, TIMEOUT, false));
    }

    @Test
    @DisplayName("a task still working is left alone")
    void progressingIsLeftAlone() {
        assertFalse(AgentLoop.shouldCancelForStall(0, TIMEOUT, false));
        assertFalse(AgentLoop.shouldCancelForStall(TIMEOUT - 1, TIMEOUT, false),
                "just inside the limit is not a stall");
        assertFalse(AgentLoop.shouldCancelForStall(TIMEOUT, TIMEOUT, false),
                "exactly at the limit is not yet past it");
    }

    @Test
    @DisplayName("asking twice does not make it notice sooner")
    void doesNotReAsk() {
        assertFalse(AgentLoop.shouldCancelForStall(TIMEOUT * 10, TIMEOUT, true),
                "cancellation is cooperative: the task has not reached a checkpoint yet, and a "
                        + "second request would only re-log every 30 seconds until it does");
    }

    @Test
    @DisplayName("a non-positive timeout disables the watchdog rather than cancelling everything")
    void zeroTimeoutIsDisabled() {
        assertFalse(AgentLoop.shouldCancelForStall(Long.MAX_VALUE, 0, false));
        assertFalse(AgentLoop.shouldCancelForStall(Long.MAX_VALUE, -1, false),
                "a misconfigured timeout must not cancel every task on its first tick");
    }

    @Test
    @DisplayName("progress resets the clock the watchdog reads")
    void markProgressResetsTheClock() throws Exception {
        AgentContext ctx = new AgentContext("u1", "t1", "do something slow");
        Thread.sleep(25);
        assertTrue(ctx.msSinceLastProgress() >= 20,
                "time must actually accumulate, or the watchdog can never trigger");
        ctx.markProgress();
        assertTrue(ctx.msSinceLastProgress() < 20,
                "a step finishing must clear the stall clock");
    }
}
