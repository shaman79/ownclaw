package com.ownclaw.core;

import com.ownclaw.agent.AgentResult;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
 */
@Service
public class ResultDelivery {

    private static final Logger log = LoggerFactory.getLogger(ResultDelivery.class);

    private final ConversationService conversations;
    private final ChatStatusEmitter statusEmitter;

    public ResultDelivery(ConversationService conversations, ChatStatusEmitter statusEmitter) {
        this.conversations = conversations;
        this.statusEmitter = statusEmitter;
    }

    /**
     * Deliver a finished task's outcome, phrased according to how it ended.
     *
     * @param label what the task was, for a header — the result arrives long after the request,
     *              so it has to say what it is answering
     */
    public void deliver(String userId, String label, AgentResult result) {
        String header;
        if (result.success()) {
            header = label;
        } else if (result.awaitingUser()) {
            header = label + " — needs an answer before it can go on";
        } else {
            header = label + " — did not finish (" + result.terminationReason() + ")";
        }
        deliver(userId, header, result.response());
    }

    /** Deliver text as a real assistant message in the user's current conversation. */
    public void deliver(String userId, String header, String text) {
        if (text == null || text.isBlank()) {
            // Nothing useful to show. Saying so beats an empty bubble, which reads like a bug.
            text = "(the task produced no output)";
        }
        String message = header == null || header.isBlank() ? text : "**" + header + "**\n\n" + text;

        try {
            String sessionId = conversations.getCurrentSession(userId);
            conversations.saveMessage(userId, sessionId, "assistant", message);
        } catch (Exception e) {
            // Persisting is the more important half, but failing it must not also lose the push.
            log.warn("Could not persist a background result for {}: {}", userId, e.getMessage());
        }
        statusEmitter.emit(userId, StatusMessage.Type.RESULT, message);
    }
}
