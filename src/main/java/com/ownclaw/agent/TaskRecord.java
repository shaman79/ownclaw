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
 * How a delegation failed is not its whole report: the report shows the cloud every result of the
 * calls that ran, and a record holds no result of a call that worked ({@link #howFailed}).
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
     * How each failed step failed -- the text its line shows ({@link #howFailed}). A result whose
     * text is one of these is on the screen already.
     */
    static Set<String> reasons(Map<String, Object> trace) {
        var out = new HashSet<String>();
        for (Map<String, Object> step : maps(trace.get("steps"))) {
            String how = howFailed(step);
            if (how != null) out.add(how);
        }
        return out;
    }

    /**
     * How a failed step failed, as the record says it, or null when its row keeps none (a step
     * that worked, a PRIVATE one): the row's reason, whole -- but of a delegation, without the
     * results of the calls that worked.
     * <p>
     * A failed delegation's reason is its whole report, as the cloud read it and as the task page
     * shows it: what had already been done, why it stopped, then every result of the calls that
     * ran, whole. The record's own closing words are that results are not carried into the later
     * task -- and in the reason they were: one failed delegation's was 273,970 characters, the
     * listing of every Home Assistant entity, which a call that worked had returned, and every
     * later turn of that chat began by sending it (a 320,174-character first block, 178,000
     * tokens written to the cache, $1.46 before any work, on 2026-11-15).
     * <p>
     * So a delegation says how it failed apart from its report, where it writes it
     * (LocalExecutor.Outcome#howFailed), and its row keeps that beside the reason
     * ({@link AgentLoop#HOW_FAILED}); it is used as it stands. A row from before that has the
     * reason alone, and the results are taken out of it here ({@link #withoutResults}). Either
     * way the line is made of the stored row and nothing else, so the record is the same bytes on
     * every later turn ({@link #forLaterTask}). Any other step's reason is its own error, and
     * stays whole.
     */
    static String howFailed(Map<String, Object> step) {
        if (step.get(AgentLoop.HOW_FAILED) instanceof String apart && !apart.isBlank()) return apart;
        if (!(step.get("reason") instanceof String reason)) return null;
        return AgentAction.DELEGATE.equals(step.get("tool")) ? withoutResults(reason) : reason;
    }

    /**
     * A failed delegation's report without the results of its calls: what stands before the line
     * that opens them, and what stands after them -- the ledger of the tools that ran and the
     * failed steps' own errors. Found by the lines the report's writer marks them with, which are
     * its constants ({@link LocalExecutor#RESULTS_OF_CALLS}): in a report of calls that stopped
     * and in one that finished with a failed step the results end where the ledger begins; one
     * that did not finish has no ledger, and its results run to its end. A report with none of
     * those lines holds no result, and is returned as it is.
     * <p>
     * A result, a summary or an error can hold a line that reads like one of these, and no result
     * is left in for it, whatever the report holds. The results are taken to begin at the FIRST
     * such line: the writer puts the real one before any result. They are taken to end at the
     * LAST line that opens a ledger: the writer puts the real one after every result -- and at
     * the report's end wherever a line opens the results of a report that did not finish, since
     * such a report has no ledger to end them. So a line of that kind can only move an end
     * outwards, and then more is taken out: the ledger and the failed steps' errors of a report
     * whose result quotes a report that did not finish, or the part of them before a failed
     * step's own "[Tools run: ". That is the price of reading it back, and why a delegation now
     * says how it failed itself: only the writer knows where a result ends.
     */
    static String withoutResults(String report) {
        int calls = lineOpening(report, LocalExecutor.RESULTS_OF_CALLS, 0, true);
        int unfinished = lineOpening(report, LocalExecutor.PARTIAL_RESULTS, 0, true);
        int finished = lineOpening(report, LocalExecutor.COMPLETED_FOR, 0, false);
        int from = -1;
        for (int opens : new int[] {calls, unfinished, finished}) {
            if (opens >= 0 && (from < 0 || opens < from)) from = opens;
        }
        if (from < 0) return report;
        int to = report.length();
        if (unfinished < 0) {
            int ledger = lastLineOpening(report, LocalExecutor.TOOLS_RUN, from);
            if (ledger >= 0) {
                to = ledger;
            } else if (from == finished && lineOpening(report, LocalExecutor.NO_TOOL_RAN, from, true) >= 0) {
                // It finished having run no tool, and says so: there is no result in it.
                return report;
            }
        }
        String before = report.substring(0, from).stripTrailing();
        // The rule that set the local model's summary off from its results goes with them.
        String rule = LocalExecutor.SUMMARY_RULE.strip();
        if (from == finished && (before.equals(rule) || before.endsWith("\n" + rule))) {
            before = before.substring(0, before.length() - rule.length()).stripTrailing();
        }
        String after = report.substring(to);
        return (before.isEmpty() || after.isEmpty() ? before + after : before + "\n\n" + after).strip();
    }

    /**
     * Where the first line of {@code text} that opens with {@code opening} begins, at or after
     * {@code from} -- the line that is {@code opening} and nothing more, when {@code whole} -- or
     * -1 when there is none.
     */
    private static int lineOpening(String text, String opening, int from, boolean whole) {
        for (int i = text.indexOf(opening, from); i >= 0; i = text.indexOf(opening, i + 1)) {
            int end = i + opening.length();
            if ((i == 0 || text.charAt(i - 1) == '\n')
                    && (!whole || end == text.length() || text.charAt(end) == '\n')) {
                return i;
            }
        }
        return -1;
    }

    /** Where the last line of {@code text} that opens with {@code opening} begins, at or after {@code from}; -1 when none does. */
    private static int lastLineOpening(String text, String opening, int from) {
        for (int i = text.lastIndexOf(opening); i >= from; i = text.lastIndexOf(opening, i - 1)) {
            if (i == 0 || text.charAt(i - 1) == '\n') return i;
        }
        return -1;
    }

    /**
     * The record a later task of the chat is given under this task's answer, or null: when the
     * task did not end COMPLETED its ending already carries the record ({@link TaskEnding}), and a
     * task with no rows has none.
     * <p>
     * The same bytes on every later turn: every turn after it sends it again, as a block of the
     * chat the cloud has cached, and a record that changed made that turn write the whole chat to
     * the cache again. So not the local tokens: a local reply billed after the ending -- a summary
     * of a private result that came back as the task ended -- is added to them later
     * ({@code TaskTraceService}), after the next turn may already have sent the record.
     */
    static String forLaterTask(String taskId, Map<String, Object> trace) {
        Map<String, Object> outcome = map(trace.get("outcome"));
        if (outcome == null || !"COMPLETED".equals(outcome.get("reason"))) return null;
        String steps = steps(trace);
        return "[OwnClaw's record of task " + taskId + ", from its step log:"
                + (steps.isEmpty() ? " no step ran." : "\n" + steps)
                + "\nIt answered after " + duration(number(outcome.get("durationMs")))
                + ", using " + tokens(number(outcome.get("cloudTokens")), 0)
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
        String how = howFailed(step);
        if (how != null && !how.isBlank()) sb.append(" — ").append(how.strip());
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
