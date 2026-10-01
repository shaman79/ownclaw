package com.ownclaw.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a task did, as OwnClaw recorded it: one line per step, made from the task's rows in
 * {@code events} as {@link com.ownclaw.observability.TaskTraceService} parses them for the owner's
 * task page. It has two readers -- the ending of a task that did not finish ({@link TaskEnding}),
 * and a later task of the same chat, under the answer of one that did
 * ({@link AgentLoop#loadConversationContext}) -- so the owner, the task page and the next turn
 * read one record, and "what happened?" is answered from it instead of by running the work again.
 * <p>
 * It is made of what the step rows hold: tool and skill names, what a skill_manage step did,
 * outcomes, durations, the model calls a step made, each result's label and size -- and, for a
 * failed step whose result was not private, how it failed, with vault values already scrubbed
 * ({@link AgentLoop#stepOutcome}). Nothing else of a result; and never the request, which another
 * row of the task holds, or an attachment's name, which none holds.
 * <p>
 * Results are named in words, never by handle: {{2}} named a result of that task, and in the task
 * that reads the record it names another one, so a handle copied from here would resolve to it.
 */
final class TaskRecord {

    private TaskRecord() {}

    /**
     * Each recorded step, one numbered line; consecutive identical lines are written once with
     * how many there were ("×2"). Empty when no step was recorded.
     */
    static String steps(Map<String, Object> trace) {
        List<Map<String, Object>> calls = maps(trace.get("calls"));
        var lines = new ArrayList<String>();
        var times = new ArrayList<Integer>();
        for (Map<String, Object> step : maps(trace.get("steps"))) {
            String line = line(step, calls);
            int last = lines.size() - 1;
            if (last >= 0 && lines.get(last).equals(line)) {
                times.set(last, times.get(last) + 1);
            } else {
                lines.add(line);
                times.add(1);
            }
        }
        var sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(i + 1).append(". ").append(lines.get(i));
            if (times.get(i) > 1) sb.append(" ×").append(times.get(i));
        }
        return inWords(sb.toString());
    }

    /** How many steps were recorded. */
    static int stepCount(Map<String, Object> trace) {
        return maps(trace.get("steps")).size();
    }

    /**
     * The skills the task's skill_create steps wrote and no later step deleted, in the order they
     * were last written.
     */
    static List<String> skillsKept(Map<String, Object> trace) {
        var kept = new LinkedHashSet<String>();
        for (Map<String, Object> step : maps(trace.get("steps"))) {
            if (!Boolean.TRUE.equals(step.get("ok")) || step.get("skill") == null) continue;
            String skill = String.valueOf(step.get("skill"));
            if (AgentAction.SKILL_CREATE.equals(step.get("tool"))) {
                kept.remove(skill);
                kept.add(skill);
            } else if (AgentAction.SKILL_MANAGE.equals(step.get("tool")) && "delete".equals(step.get("skillAction"))) {
                kept.remove(skill);
            }
        }
        return List.copyOf(kept);
    }

    /**
     * How each failed step failed, as its row keeps it -- the text its line shows. A result whose
     * text is one of these is on the screen already.
     */
    static Set<String> reasons(Map<String, Object> trace) {
        var out = new HashSet<String>();
        for (Map<String, Object> step : maps(trace.get("steps"))) {
            if (step.get("reason") instanceof String how) out.add(how);
        }
        return out;
    }

    /**
     * The record a later task of the chat is given under this task's answer, or null: when the
     * task did not end COMPLETED its ending already carries the record ({@link TaskEnding}), and a
     * task with no rows has none.
     */
    static String forLaterTask(String taskId, Map<String, Object> trace) {
        Map<String, Object> outcome = map(trace.get("outcome"));
        if (outcome == null || !"COMPLETED".equals(outcome.get("reason"))) return null;
        String steps = steps(trace);
        return "[OwnClaw's record of task " + taskId + ", from its step log:"
                + (steps.isEmpty() ? " no step ran." : "\n" + steps)
                + "\nIt answered after " + duration(number(outcome.get("durationMs")))
                + ", using " + tokens(number(outcome.get("cloudTokens")), number(outcome.get("localTokens")))
                + ". Its results are not carried into this task: only the answer above is.]";
    }

    /**
     * Every handle in {@code text} -- {{2}}, {{2.body_text}} -- written as words: "result 2",
     * "result 2.body_text". For text saved to the chat or given to another task, where a handle
     * would resolve against that task's own results.
     */
    static String inWords(String text) {
        if (text == null) return null;
        var m = ArtifactRef.TOKEN.matcher(text);
        var sb = new StringBuilder();
        while (m.find()) {
            ArtifactRef ref = ArtifactRef.parse(m.group());
            String words = ref == null ? "result ?"
                    : "result " + ref.handle() + (ref.field() == null ? "" : "." + ref.field());
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(words));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** "450ms", "2.1s", "9m 22s", "1h 2m 3s". */
    static String duration(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60_000) return String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
        long s = ms / 1000;
        return s < 3600 ? (s / 60) + "m " + (s % 60) + "s"
                : (s / 3600) + "h " + (s % 3600 / 60) + "m " + (s % 60) + "s";
    }

    /** "324,866 cloud tokens", and the local ones when there were any. */
    static String tokens(long cloud, long local) {
        return String.format(Locale.ROOT, "%,d cloud tokens", cloud)
                + (local > 0 ? String.format(Locale.ROOT, " + %,d local", local) : "");
    }

    /**
     * One step: how it went, what ran -- for skill_manage, which action on which skill -- how
     * long, the model calls it made beyond the one that chose it, what it produced, and how it
     * failed.
     */
    private static String line(Map<String, Object> step, List<Map<String, Object>> calls) {
        var sb = new StringBuilder(Boolean.TRUE.equals(step.get("ok")) ? "✓ " : "✗ ");
        sb.append(step.get("tool"));
        if (step.get("skillAction") != null) sb.append(' ').append(step.get("skillAction"));
        if (step.get("skill") != null) sb.append(' ').append(step.get("skill"));
        long ms = number(step.get("durationMs"));
        if (ms > 0) sb.append(" · ").append(duration(ms));
        String made = callsFor(step.get("step"), calls);
        if (!made.isEmpty()) sb.append(" · ").append(made);
        for (Map<String, Object> result : maps(step.get("artifacts"))) {
            sb.append(" → ").append(result.get("handle")).append(", ")
              .append(String.format(Locale.ROOT, "%,d chars", number(result.get("chars"))));
            if ("PRIVATE".equals(result.get("label"))) {
                sb.append(", private");
                List<?> why = result.get("why") instanceof List<?> l ? l : List.of();
                if (!why.isEmpty()) sb.append(" (").append(String.join("; ", why.stream().map(String::valueOf).toList())).append(')');
            } else {
                sb.append(", public");
            }
        }
        if (step.get("reason") instanceof String how && !how.isBlank()) sb.append(" — ").append(how.strip());
        return sb.toString();
    }

    /**
     * The calls a step made other than the "think" that chose it -- code generation, a library
     * analysis -- by purpose, with the output tokens they were billed for. Writing a skill's code
     * is most of what a skill_create costs and takes, and nothing else in the row says so.
     */
    private static String callsFor(Object stepNo, List<Map<String, Object>> calls) {
        var count = new LinkedHashMap<String, Integer>();
        long out = 0;
        for (Map<String, Object> c : calls) {
            if (stepNo == null || !stepNo.equals(c.get("step")) || "think".equals(c.get("purpose"))) continue;
            count.merge(String.valueOf(c.get("purpose")), 1, Integer::sum);
            out += number(c.get("completionTokens"));
        }
        if (count.isEmpty()) return "";
        var parts = new ArrayList<String>();
        count.forEach((purpose, n) -> parts.add(n + " " + purpose + (n == 1 ? " call" : " calls")));
        return String.join(", ", parts) + String.format(Locale.ROOT, " (%,d output tokens)", out);
    }

    private static long number(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        var out = new ArrayList<Map<String, Object>>();
        for (Object x : l) if (x instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }
}
