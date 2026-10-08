package com.ownclaw.agent;

import com.ownclaw.agent.AgentResult.TerminationReason;
import com.ownclaw.privacy.Redactor;
import com.ownclaw.llm.EgressRefused;
import com.ownclaw.llm.LlmException;
import com.ownclaw.llm.ProviderRefused;
import com.ownclaw.privacy.PrivateIndex;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * How a task ends, written by code: for a task that did not finish, why it stopped, what it did
 * (its {@link TaskRecord}), what it produced and where each result is now, and what comes next.
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
 * values -- a finished answer's too, the one thing done to it. The ending's own lines, and the
 * question of a task waiting for an answer, name results in words, never by handle
 * ({@link TaskRecord#inWords}): the next task, which reads them and carries the work on, numbers
 * its results from 1 again, so a handle copied from here would resolve to one of those. A result
 * quoted in full is quoted as it was written -- a template's {{1}} is its text, not a handle.
 */
final class TaskEnding {

    private TaskEnding() {}

    /**
     * The ending of {@code r}, written around the loop's why ({@code r.response()}); a question
     * keeps the question on top. A finished answer is returned as the model placed it, with vault
     * values scrubbed out, and a line under it when the local model took the task over.
     *
     * @param trace the task's rows as {@code TaskTraceService} parses them -- the steps it did
     */
    static AgentResult apply(AgentResult r, AgentContext ctx, Map<String, Object> trace) {
        if (r.terminationReason() == TerminationReason.COMPLETED) {
            // Said under the answer too: a scheduled report has no chat note to say it.
            String local = ctx.onLocal() ? "\n\n_🏠 Written by the local model: the cloud model could not "
                    + "be used -- " + ctx.onLocalBecause() + "._" : "";
            return texts(r, scrubbed(r.response(), ctx) + local,
                    r.ownerText() == null ? null : scrubbed(r.ownerText(), ctx) + local);
        }
        boolean question = r.awaitingUser();
        var ending = new StringBuilder();
        if (!question) ending.append(words("**Stopped:** " + sentence(r.response()) + "\n\n", ctx));
        ending.append(words(did(ctx, trace), ctx));
        var privateResults = new StringBuilder();
        ending.append(produced(ctx, trace, privateResults));
        String next = next(r.terminationReason(), ctx.isUnattended());
        if (next != null) {
            ending.append("\n\n**Next:** ").append(next)
                  .append(" Every step is on this task's page: task ").append(ctx.taskId()).append('.');
        }

        String response = question ? words(r.response(), ctx) + "\n\n" + ending : ending.toString();
        String ownerText = question && r.ownerText() != null ? words(r.ownerText(), ctx) + "\n\n" + ending : null;
        if (privateResults.length() > 0) ownerText = (ownerText != null ? ownerText : response) + privateResults;
        return texts(r, response, ownerText);
    }

    /**
     * Why the model's provider gave no answer, in words, by the category it named. The refusal's
     * own message -- "stop reason: refusal (reasoning_extraction)" -- used to be all the owner
     * was shown, and the owner had to ask what it meant.
     */
    static String declined(ProviderRefused declined) {
        String who = "the cloud model's provider (" + declined.getProvider() + ")";
        if (declined.asReasoningExtraction()) {
            return who + " stopped the step as reasoning extraction -- a safety check against giving "
                    + "away the model's hidden reasoning -- and did so again after the model was asked for "
                    + "no progress updates beside its calls, and again when it was no longer shown those "
                    + "it had written";
        }
        String category = declined.category();
        return who + " declined to answer this request" + (category == null
                ? " (" + declined.stopReason() + ")"
                : ": its safety check placed it in the category \"" + category + "\"");
    }

    /**
     * Why the cloud model cannot be used, in words: the provider's reason, and for a failed
     * connection that the internet may be down -- the local model is on the LAN, so it is said of
     * the cloud only. The chat's note, the local model's prompt and the task's ending say it so.
     */
    static String cloudUnreachable(LlmException why) {
        return why.unreachableBecause() + (why.connectionFailed() ? ", and the internet may be down" : "");
    }

    /**
     * Why no model could take the task on, in words: the local model could not be reached --
     * and, the task running local only, that the cloud is switched off -- or the cloud could not
     * be, and the local model was not there to go on with it. Local only is Free in time vs cost,
     * the chat's own or the default it follows ({@link TaskOptions}): either way, choosing
     * Fastest or Cheaper next to the message box switches the cloud on for that chat.
     */
    static String unreachable(LlmException why, boolean local, boolean localOnly) {
        if (local) {
            return "the local model (" + why.getProvider() + ") could not be reached -- "
                    + why.unreachableBecause()
                    + (localOnly ? " -- and the cloud model is switched off (time vs cost is set to Free; "
                    + "Fastest or Cheaper next to the message box switches it on)" : "");
        }
        return "the cloud model (" + why.getProvider() + ") could not be used -- " + cloudUnreachable(why)
                + " -- and the local model is not available to go on with the task";
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
     * ones in full go to {@code privateResults}, for the owner's text only.
     * <p>
     * A result already on the screen is not repeated: one the question placed, or the text of a
     * failed step, which its line in "What it did" shows -- when the text there is exactly the
     * result's, which it is not when the result holds what reads as a handle, written above in
     * words. Only those: a result that merely occurs somewhere in the text above (a count of 3 in
     * "3 steps") is still shown. A file the owner sent is described, never repeated: he has it.
     */
    private static String produced(AgentContext ctx, Map<String, Object> trace, StringBuilder privateResults) {
        Set<String> failures = TaskRecord.reasons(trace);
        var list = new StringBuilder();
        var publicResults = new StringBuilder();
        for (Artifact a : ctx.artifacts()) {
            String name = "result " + a.n() + " (" + a.tool() + ")";
            String heading = "**" + name + (a.succeeded() ? "" : ", failed") + ":**\n\n";
            String text = scrubbed(a.output(), ctx);
            list.append("\n- ").append(name).append(": ").append(a.succeeded() ? "" : "failed, ")
                .append(String.format(Locale.ROOT, "%,d chars", a.output().length()))
                .append(a.isPrivate() ? ", private (" + String.join("; ", a.why()) + ")" : ", public");
            // The gateway's own question of the text: a run of a private result the cloud was
            // never given. Such a public result goes where the private one does.
            PrivateIndex.Hit repeats = a.isPrivate() ? null : ctx.firstLeakIn(a.output());
            if ("attachment".equals(a.tool()) || a.output().isEmpty()) {
                list.append('.');
            } else if ((ctx.isShown(a) || failures.contains(text)) && TaskRecord.inWords(text).equals(text)) {
                list.append(" — shown above.");
            } else if (a.isPrivate() || repeats != null) {
                list.append(a.isPrivate() ? " — shown to you only, never to the cloud model."
                        : " — it repeats text of result " + repeats.handle() + " ("
                                + ctx.artifacts().get(repeats.handle() - 1).tool() + "), which is private, "
                                + "so it is shown to you only.");
                privateResults.append("\n\n")
                        .append("local_answer".equals(a.tool()) ? AgentLoop.PRIVATE_HEADER : AgentLoop.PRIVATE_RESULT_HEADER)
                        .append(heading).append(text);
            } else {
                list.append(" — in full below.");
                publicResults.append("\n\n").append(heading).append(text);
            }
        }
        for (String skill : TaskRecord.skillsKept(trace)) {
            list.append("\n- The skill ").append(skill).append(": written, and kept for later tasks.");
        }
        return list.length() == 0 ? "\n\n**What it produced:** nothing."
                : "\n\n**What it produced:**" + words(list.toString(), ctx) + publicResults;
    }

    /**
     * What the owner can do next -- only what is true for this ending.
     * <p>
     * An unattended run's ending -- a scheduled run's, or a /bg task's -- is a report delivered
     * into a chat of its own: the pinned chat of scheduled results, or the one /bg was typed in.
     * A message reads it when it is sent in that chat; the report goes to Telegram too, where a
     * message is filed in whichever chat is open on the web, so "your next message reads this"
     * was false there. Its ending says where.
     */
    private static String next(TerminationReason reason, boolean unattended) {
        String in = unattended ? " in the web chat that holds this report" : "";
        return switch (reason) {
            case COMPLETED, NEEDS_INPUT -> null;
            case PRIVACY_BLOCKED -> "Your next message" + in + " starts a new task, which reads this message "
                    + "but not the private results.";
            // "Your next message starts a new task, which reads this message" was literally true
            // and sent the owner back into a chat whose every later task read the whole of it,
            // this ending too, and failed the same way.
            case CONTEXT_WINDOW -> "A message sent" + (unattended ? in : " in this chat") + " is read with "
                    + "the whole chat, this ending included, so it is likely to be too long as well: start "
                    + "a new chat (/new) to carry on, and say there what it needs from this one.";
            default -> "Your next message" + in + " starts a new task, which reads this message.";
        };
    }

    private static AgentResult texts(AgentResult r, String response, String ownerText) {
        return new AgentResult(r.success(), response, r.trajectory(), r.totalSteps(),
                r.totalDurationMs(), r.terminationReason(), r.taskId(), ownerText);
    }

    /** What OwnClaw wrote: scrubbed of vault values, then handles written as words -- in that order, so a value is scrubbed whole. */
    private static String words(String text, AgentContext ctx) {
        return TaskRecord.inWords(scrubbed(text, ctx));
    }

    /** A result, or a finished answer: as it was written but for vault values. */
    private static String scrubbed(String text, AgentContext ctx) {
        return text == null ? null : Redactor.scrubVault(text, ctx.secretValues()).text();
    }
}
