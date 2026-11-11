package com.ownclaw.agent;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Each owner's usual skills -- the ones every task is offered from its first step -- as they stood
 * when his day began (UTC): worked out by his first task of the day and the same for every task
 * after it, until the day ends.
 * <p>
 * They are part of the front of every request to the cloud: on a Fastest chat each is offered
 * whole, not deferred; on a Cheaper one delegate's description names them. Worked out afresh for
 * every task -- the five he has run most, all time -- the set changed whenever a run moved a
 * skill into the five or out of it, a skill's first run among them, and the next turn of every
 * chat wrote its whole conversation to the cache again. Now it changes at most once a day, at
 * midnight UTC -- one or two at night in Prague -- when a chat is seldom under way.
 */
final class UsualSkills {

    /** One owner's usual skills, and the day they are for. */
    private record Day(LocalDate date, List<String> names) {}

    private final Function<String, List<String>> mostUsed;
    private final Clock clock;
    private final Map<String, Day> days = new ConcurrentHashMap<>();

    /**
     * @param mostUsed an owner's usual skills as they stand now, most used first
     * @param clock    whose UTC date is the day
     */
    UsualSkills(Function<String, List<String>> mostUsed, Clock clock) {
        this.mostUsed = mostUsed;
        this.clock = clock;
    }

    /**
     * The owner's usual skills today: worked out by his first task of the day, then the same. A
     * failure to work them out is thrown, and the next task tries again.
     */
    List<String> of(String userId) {
        LocalDate today = LocalDate.now(clock.withZone(java.time.ZoneOffset.UTC));
        return days.compute(userId, (user, day) -> day != null && day.date().equals(today) ? day
                : new Day(today, List.copyOf(mostUsed.apply(user)))).names();
    }
}
