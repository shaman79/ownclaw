package com.ownclaw.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The owner's usual skills are the front of every request's cached prompt, so they hold for a whole
 * UTC day: worked out by his first task of the day, whatever runs after it.
 */
class UsualSkillsTest {

    /** A clock the test moves. */
    static final class Moved extends Clock {
        Instant now;

        Moved(String at) { now = Instant.parse(at); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    @DisplayName("the same for the rest of the UTC day, whatever runs; each owner his own; worked out again on the next day")
    void theSameForTheDay() {
        var clock = new Moved("2026-11-11T22:59:00Z");   // 23:59 in Prague: the UTC day, not the local one
        var standing = new AtomicReference<>(List.of("smtp_send_email", "web_fetch"));
        var asked = new AtomicInteger();
        var usual = new UsualSkills(user -> {
            asked.incrementAndGet();
            return standing.get();
        }, clock);

        assertEquals(List.of("smtp_send_email", "web_fetch"), usual.of("u1"));
        standing.set(List.of("ha_api", "smtp_send_email"));   // a run moved ha_api into the five
        clock.now = Instant.parse("2026-11-11T23:59:59Z");
        assertEquals(List.of("smtp_send_email", "web_fetch"), usual.of("u1"), "the rest of the day: as it began");
        assertEquals(1, asked.get(), "worked out once");
        assertEquals(List.of("ha_api", "smtp_send_email"), usual.of("u2"), "each owner by his own first task");

        clock.now = Instant.parse("2026-11-12T00:00:01Z");
        assertEquals(List.of("ha_api", "smtp_send_email"), usual.of("u1"), "a new day: worked out again");
        // Mutation: work them out for every task -> the second call returns ha_api's set mid-day.
    }

    @Test
    @DisplayName("a failure to work them out keeps nothing: the next task tries again")
    void aFailureIsNotKept() {
        var clock = new Moved("2026-11-11T08:00:00Z");
        var fail = new AtomicReference<>(true);
        var usual = new UsualSkills(user -> {
            if (fail.get()) throw new IllegalStateException("database locked");
            return List.of("smtp_send_email");
        }, clock);
        assertThrows(IllegalStateException.class, () -> usual.of("u1"));
        fail.set(false);
        assertEquals(List.of("smtp_send_email"), usual.of("u1"));
    }
}
