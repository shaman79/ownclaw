package com.ownclaw.core;

import com.ownclaw.agent.AgentResult;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Delivers the result of work nobody was sitting and waiting for.
 * <p>
 * Both paths that produce unattended results dropped them. {@code /bg} promised "you will get the
 * result here when it finishes" and then discarded the future outright, so nothing was ever sent.
 * A scheduled run stored its output in {@code scheduled_tasks.last_result} and emitted a status
 * line — "Recurring task #3 completed. Next run: 07:00" — so a morning digest was produced in
 * full, written to the database, and never shown to anyone; the only way to read it was a SQL
 * query. Work that runs while you are away is the point of running it in the background, and
 * both ways of starting it silently swallowed the answer.
 * <p>
 * Delivery is two things, and it needs both. The message is <em>persisted</em> to the
 * conversation, because unattended work finishes when by definition nobody is watching and a push
 * to a closed socket goes nowhere — persistence is what makes it there when you next open the
 * chat. It is also <em>emitted</em>, so that if an interface is attached it appears immediately.
 * <p>
 * Which chat it is saved into is the caller's to say: the one {@code /bg} was typed in, or the
 * pinned chat of scheduled results. It used to be whichever chat was open when the work finished,
 * so a morning digest landed in the middle of an unrelated conversation.
 * <p>
 * A task's result goes with what it offers to click ({@link AgentResult#choices}), as an attended
 * answer does: saved with the message and sent with it, for the page's buttons and Telegram's
 * keys. Delivered without them, the command a task's ending offers for a skill that waits for a
 * grant -- {@code /grant <skill> <KEYS>}, written by code -- reached the owner of a scheduled run
 * or {@code /bg} as text to type.
 */
@Service
public class ResultDelivery {

    private static final Logger log = LoggerFactory.getLogger(ResultDelivery.class);

    /** The key of a delivered result's data that holds what it offers to click ({@link #choicesOf}). */
    public static final String CHOICES = "choices";

    private final ConversationService conversations;
    private final ChatStatusEmitter statusEmitter;

    public ResultDelivery(ConversationService conversations, ChatStatusEmitter statusEmitter) {
        this.conversations = conversations;
        this.statusEmitter = statusEmitter;
    }

    /**
     * Deliver a finished task's outcome, phrased according to how it ended.
     * <p>
     * A task that did not finish is delivered as its ending, which says why it stopped and lists
     * every result it produced ({@code TaskEnding}): the header says only that it did not finish,
     * and {@link #withheldLine} goes under a finished answer alone, which lists no results. Under
     * an ending the line was a second count, beneath a list of every result, and it counted fewer;
     * the header named the reason the ending's first line gives.
     *
     * @param chat  the chat it is saved into, as {@link #deliver(String, Supplier, String, String,
     *              String, String, List)} takes it
     * @param label what the task was, for a header — the result arrives long after the request,
     *              so it has to say what it is answering
     */
    public void deliver(String userId, Supplier<String> chat, String label, AgentResult result) {
        String header;
        if (result.success()) {
            header = label;
        } else if (result.awaitingUser()) {
            header = label + " — needs an answer before it can go on";
        } else {
            header = label + " — did not finish";
        }
        String withheld = result.success() ? withheldLine(result) : "";
        deliver(userId, chat, header, result.response() + withheld, result.taskId(),
                result.ownerText() == null ? null : result.ownerText() + withheld, result.choices());
    }

    /**
     * One line, computed from the run, on what stayed on this machine — worded as what was
     * checked, never as a claim about what left.
     * <p>
     * Both kinds of step report their artifacts on the observation: a delegation lists the steps
     * it ran, and a privately-executed direct call reports itself. An earlier version read only
     * the delegation's list and justified it with "an unattended run executes through
     * delegations" — false exactly when it matters, because the valve that turns delegation off
     * after a failure is what runs on a bad morning. On that run every result was withheld and
     * this line said nothing at all.
     * <p>
     * Known limit, stated rather than papered over: a PUBLIC direct call reports no artifacts on
     * its observation and is not counted, so on a mixed run {@code total} is a floor.
     */
    static String withheldLine(AgentResult result) {
        if (result == null || result.trajectory() == null) return "";
        int total = 0, withheldCount = 0;
        var withheld = new java.util.LinkedHashSet<String>();
        for (var turn : result.trajectory().turns()) {
            if (turn.observation() == null || turn.observation().structured() == null) continue;
            Object arts = turn.observation().structured().get("artifacts");
            if (!(arts instanceof java.util.List<?> list)) continue;
            for (Object o : list) {
                if (!(o instanceof java.util.Map<?, ?> a)) continue;
                total++;
                if ("PRIVATE".equals(String.valueOf(a.get("label")))) {
                    // The COUNT of withheld results, and separately the NAMES of the tools that
                    // produced them. Printing the size of a name set beside a result count read
                    // as "3 results, 1 withheld" when one skill had been called twice.
                    withheldCount++;
                    withheld.add(String.valueOf(a.get("tool")));
                }
            }
        }
        if (total == 0) return "";
        return "\n\n_" + total + " result" + (total == 1 ? "" : "s") + ", " + withheldCount
                + " withheld from the cloud" + (withheld.isEmpty() ? "" : " (" + String.join(", ", withheld) + ")")
                + "_";
    }

    /**
     * {@link #deliver(String, Supplier, String, String, String, String, List)}, offering nothing
     * to click: for a caller that has no task's result to deliver, only text of its own.
     */
    public void deliver(String userId, Supplier<String> chat, String header, String text, String taskId,
                        String ownerText) {
        deliver(userId, chat, header, text, taskId, ownerText, List.of());
    }

    /**
     * Deliver text as a real assistant message in the chat the caller names.
     *
     * @param chat      the chat it is saved into, looked up here as part of saving it: finding the
     *                  pinned chat of scheduled results can create it, and fail like any write.
     *                  Looked up by the caller, a failure escaped before this was called, and the
     *                  result was neither saved nor sent. Sent with the message, so a page showing
     *                  another chat does not append it there.
     * @param taskId    the agent task this is the outcome of, or null. Saved with the message and
     *                  sent with it, so the chat can offer "what this task did" -- now and after a
     *                  reload.
     * @param ownerText what the owner is shown in place of {@code text} -- web chat and
     *                  Telegram -- or null. Saved as the row's private text and sent only in the
     *                  message's data; the message's text stays the safe one, and that is what
     *                  history, search and later prompts read.
     * @param choices   what the task's result offers to click ({@link AgentResult#choices}), passed
     *                  on as it has them and never made here: saved with the message, as an
     *                  attended answer's are, and sent in its data ({@link #choicesOf}) -- the page
     *                  draws them under it, now and after a reload, and Telegram as keys. A click
     *                  only sends one as the owner's own message.
     */
    public void deliver(String userId, Supplier<String> chat, String header, String text, String taskId,
                        String ownerText, List<String> choices) {
        if (text == null || text.isBlank()) {
            // Nothing useful to show. Saying so beats an empty bubble, which reads like a bug.
            text = "(the task produced no output)";
        }
        String message = withHeader(header, text);
        String owner = ownerText == null ? null : withHeader(header, ownerText);
        List<String> offered = choices == null ? List.of() : List.copyOf(choices);

        String sessionId = null;
        String savedAt = null;
        try {
            sessionId = chat.get();
            savedAt = conversations.timestampOf(conversations.saveResult(userId, sessionId, message, taskId, owner,
                    offered));
        } catch (Exception e) {
            // Persisting is the more important half, but failing it must not also lose the push.
            log.warn("Could not persist a background result for {}: {}", userId, e.getMessage());
        }
        var data = new java.util.HashMap<String, Object>();
        if (sessionId != null) data.put("sessionId", sessionId);
        // When it was saved, so the page shows the time a reload of the chat will.
        if (savedAt != null) data.put("timestamp", savedAt);
        if (owner != null) data.put("ownerText", owner);
        if (!offered.isEmpty()) data.put(CHOICES, offered);
        if (taskId == null) statusEmitter.emit(userId, StatusMessage.Type.RESULT, message, data);
        else statusEmitter.emitForTask(userId, taskId, StatusMessage.Type.RESULT, message, data);
    }

    /**
     * What a delivered result offers to click, as {@link #deliver} sent it in the result's data:
     * none for a status of any other kind, and none for a result that offers nothing. The one
     * reader of it -- the web chat's frame and Telegram's keys both take theirs from here.
     */
    public static List<String> choicesOf(StatusMessage msg) {
        if (msg == null || msg.type() != StatusMessage.Type.RESULT || msg.data() == null
                || !(msg.data().get(CHOICES) instanceof List<?> offered)) {
            return List.of();
        }
        var choices = new ArrayList<String>();
        for (Object choice : offered) {
            if (choice instanceof String c) choices.add(c);
        }
        return List.copyOf(choices);
    }

    private static String withHeader(String header, String text) {
        return header == null || header.isBlank() ? text : "**" + header + "**\n\n" + text;
    }
}
