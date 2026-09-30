package com.ownclaw.agent;

import com.ownclaw.agent.AgentResult.TerminationReason;
import com.ownclaw.llm.CloudGateway;
import com.ownclaw.llm.EgressRefused;

import java.util.Locale;
import java.util.Map;

/**
 * How a task that did not finish ends, written by code: why it stopped, what it did (its
 * {@link TaskRecord}), what it produced and where each result is now, and what comes next.
 * <p>
 * The ending is the task's chat row, so it is also what the next turn reads about the task and
 * what its episode stores. Each exit of the loop used to type its own sentence -- "Task was
 * cancelled.", the gateway's own message -- so an eleven-minute run that wrote and lost a skill
 * ended on four words, the next turn read the same four words and started the work again, and a
 * private audit the owner had asked for ended with the task.
 * <p>
 * Two texts. {@code response}, which every later prompt, the history and the episode read, holds
 * each PUBLIC result in full and no PRIVATE one. {@code ownerText}, which only the owner's own
 * screens show, is the same with the private results in full after it. Both are scrubbed of vault
 * values and name results in words, never by handle ({@link TaskRecord#inWords}).
 */
final class TaskEnding {

    private TaskEnding() {}

    /**
     * The ending of {@code r}, written around the loop's why ({@code r.response()}); a question
     * keeps the question on top. A finished answer is returned as it is.
     *
     * @param trace the task's rows as {@code TaskTraceService} parses them -- the steps it did
     */
    static AgentResult apply(AgentResult r, AgentContext ctx, Map<String, Object> trace) {
        if (r.terminationReason() == TerminationReason.COMPLETED) return r;
        boolean question = r.awaitingUser();
        var ending = new StringBuilder();
        if (!question) ending.append("**Stopped:** ").append(sentence(r.response())).append("\n\n");
        String did = did(ctx, trace);
        ending.append(did);
        var privateResults = new StringBuilder();
        ending.append(produced(ctx, trace, (r.ownerText() == null ? "" : r.ownerText()) + did, privateResults));
        String next = next(r.terminationReason());
        if (next != null) {
            ending.append("\n\n**Next:** ").append(next)
                  .append(" Every step is on this task's page: task ").append(ctx.taskId()).append('.');
        }

        String response = question ? r.response() + "\n\n" + ending : ending.toString();
        String ownerText = question && r.ownerText() != null ? r.ownerText() + "\n\n" + ending : null;
        if (privateResults.length() > 0) ownerText = (ownerText != null ? ownerText : response) + privateResults;
        return new AgentResult(r.success(), chatSafe(response, ctx), r.trajectory(), r.totalSteps(),
                r.totalDurationMs(), r.terminationReason(), r.taskId(),
                ownerText == null ? null : chatSafe(ownerText, ctx));
    }

    /**
     * Why the gateway refused, in words: which private result, from which tool, why it is
     * private, and where in the request its text was found. The refusal's own message is an
     * operator's line -- part numbers and offsets -- and it used to be the whole of what the owner
     * was shown, with "see task X in ops" and nothing he could do.
     */
    static String blocked(EgressRefused refused, AgentContext ctx) {
        String tool = refused.tool();
        if (tool != null && tool.startsWith("vault:")) {
            return "the next request to the cloud model still held the value of your credential "
                    + tool.substring("vault:".length()) + " after it was scrubbed, so nothing was sent";
        }
        int n = refused.handle();
        if (n < 1 || n > ctx.artifacts().size()) {
            return "the privacy check refused the next request to the cloud model, so nothing was "
                    + "sent (" + refused.getMessage() + ")";
        }
        Artifact a = ctx.artifacts().get(n - 1);
        return "the next request to the cloud model held text of result " + a.n() + " (" + a.tool()
                + "), which is private (" + String.join("; ", a.why()) + "); it was found in a "
                + refused.partKind() + " message, so nothing was sent";
    }

    /** The loop's why, as the end of "Stopped:". */
    private static String sentence(String why) {
        String s = why == null || why.isBlank() ? "it stopped" : why.strip();
        return s.endsWith(".") ? s : s + ".";
    }

    private static String did(AgentContext ctx, Map<String, Object> trace) {
        int n = TaskRecord.stepCount(trace);
        String steps = TaskRecord.steps(trace);
        return "**What it did** — " + n + (n == 1 ? " step, " : " steps, ")
                + TaskRecord.tokens(ctx.cloudTokens(), ctx.localTokens()) + ", "
                + TaskRecord.duration(ctx.elapsedMs())
                + (steps.isEmpty() ? ": no step finished." : ":\n" + steps);
    }

    /**
     * Every result of the task and where it is now, then the public ones in full; the private
     * ones in full go to {@code privateResults}, for the owner's text only. A result whose text is
     * already above -- the question showed it, or it is how a step failed -- is not repeated. A
     * file the owner sent is described, never repeated: he has it.
     */
    private static String produced(AgentContext ctx, Map<String, Object> trace, String above,
                                   StringBuilder privateResults) {
        var list = new StringBuilder();
        var publicResults = new StringBuilder();
        for (Artifact a : ctx.artifacts()) {
            String name = "result " + a.n() + " (" + a.tool() + ")";
            list.append("\n- ").append(name).append(": ").append(a.succeeded() ? "" : "failed, ")
                .append(String.format(Locale.ROOT, "%,d chars", a.output().length()))
                .append(a.isPrivate() ? ", private (" + String.join("; ", a.why()) + ")" : ", public");
            if ("attachment".equals(a.tool()) || a.output().isEmpty()) {
                list.append('.');
            } else if (above.contains(a.output()) || above.contains(chatSafe(a.output(), ctx).strip())) {
                list.append(" — shown above.");
            } else if (a.isPrivate() || ctx.privateIndex().firstHitIn(a.output()) != null) {
                // A public result that repeats a private one's text is kept from the cloud like it.
                list.append(a.isPrivate() ? " — shown to you only, never to the cloud model."
                        : " — it repeats text of a private result, so it is shown to you only.");
                privateResults.append("\n\n")
                        .append("local_answer".equals(a.tool()) ? AgentLoop.PRIVATE_HEADER : AgentLoop.PRIVATE_RESULT_HEADER)
                        .append("**").append(name).append(a.succeeded() ? "" : ", failed").append(":**\n\n")
                        .append(a.output());
            } else {
                list.append(" — in full below.");
                publicResults.append("\n\n**").append(name).append(a.succeeded() ? "" : ", failed").append(":**\n\n")
                        .append(a.output());
            }
        }
        for (String skill : TaskRecord.skillsCreated(trace)) {
            list.append("\n- The skill ").append(skill).append(": created, and kept for later tasks.");
        }
        return list.length() == 0 ? "\n\n**What it produced:** nothing."
                : "\n\n**What it produced:**" + list + publicResults;
    }

    /** What the owner can do next -- only what is true for this ending. */
    private static String next(TerminationReason reason) {
        return switch (reason) {
            case COMPLETED, NEEDS_INPUT -> null;
            case MAX_STEPS -> "Reply **continue** to carry on: a new task starts from this message.";
            case PRIVACY_BLOCKED -> "Your next message starts a new task, which reads this message "
                    + "but not the private results.";
            default -> "Your next message starts a new task, which reads this message.";
        };
    }

    /** Scrubbed of vault values, then handles written as words -- in that order, so a value is scrubbed whole. */
    private static String chatSafe(String text, AgentContext ctx) {
        return TaskRecord.inWords(CloudGateway.scrub(text, ctx.secretValues()).text());
    }
}
