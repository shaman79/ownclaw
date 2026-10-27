package com.ownclaw.core;

import com.ownclaw.agent.TaskChat;
import com.ownclaw.conversation.ChatOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ObjLongConsumer;

/**
 * The messages sent to one running task in its chat: offered while it runs, read by the task
 * before each step, and handed on as tasks of their own once it has ended. One lock over the
 * three, so each message is read by the task or handed on -- never both, and never neither: an
 * offer that comes after the close is refused, and its caller queues the message itself.
 * <p>
 * It takes only what the task can take ({@link #offer}). The first message it cannot take ends
 * its taking, as the close does: that message is queued, and what is sent after it queues behind
 * it, so the task reads nothing sent later before that message has run.
 */
public final class Inbox {

    /** A message offered, with the place in the queue it took when it was sent. */
    private record Sent(UserMessage message, long order) {}

    /** Where the task came from: the one place its answer is sent. */
    private final TaskChat.Channel channel;
    /** What the task's message was sent with: what the task runs on. */
    private final ChatOptions options;
    private final List<Sent> waiting = new ArrayList<>();
    private boolean taking = true;
    /** Whether the task waits for the answer to a question it asked in its chat ({@link #awaitAnswer}). */
    private boolean asking;
    /** The answer to that question, once it has come; null until then. */
    private UserMessage answer;

    public Inbox(TaskChat.Channel channel, ChatOptions options) {
        this.channel = channel;
        this.options = options;
    }

    /**
     * Take a message for the task, unless it cannot take it: one from another channel, which the
     * task's answer would not reach; one with files -- those become a task's private files
     * when it starts, and added to a running task they would make its later results private
     * part-way through, past the check a task holding files starts with
     * ({@code AgentLoop.stopWithoutLocalModel}); or one sent with other options than the task's
     * -- time vs cost or the thinking effort chosen otherwise next to the message box -- which,
     * read by the task, would run on what the task runs on: a message sent on Free read by the
     * cloud model.
     *
     * @param order its place in the queue, taken when it was sent ({@link TaskQueue#steer}): where
     *              it runs if the task ends without reading it
     * @return false when the task has ended or takes nothing more: the message is then the
     *         caller's to queue
     */
    public synchronized boolean offer(UserMessage message, long order) {
        // The answer to the question the task waits on: the next message from its chat's channel,
        // whatever it was sent with -- it is a reply, read by the code that asked, never run on
        // other options. One with files is no answer.
        if (asking && taking && answer == null && message.channel() == channel
                && message.attachmentIds().isEmpty()) {
            answer = message;
            notifyAll();
            return true;
        }
        if (message.channel() != channel || !message.attachmentIds().isEmpty()
                || !message.options().equals(options)) taking = false;
        if (!taking) return false;
        waiting.add(new Sent(message, order));
        return true;
    }

    /** The messages offered since the task last read, in the order they came: the task reads them now. */
    public synchronized List<UserMessage> drain() {
        List<UserMessage> read = waiting.stream().map(Sent::message).toList();
        waiting.clear();
        return read;
    }

    /**
     * The task has ended: from now on no message is taken, and each one it did not read is handed
     * to {@code handOn} with the place it took when it was sent, in order, before this returns --
     * so before any offer refused from here on, whose caller queues its message after these.
     */
    /**
     * The task is about to ask a question in its chat: from now on the next message sent there is
     * its answer ({@link #awaitAnswer}). Called before the question is posted, so an answer that
     * comes at once is not taken for one more message for the task.
     */
    public synchronized void startAsking() {
        asking = true;
        answer = null;
    }

    /**
     * Wait for the answer to the question asked since {@link #startAsking}: the first message sent
     * to the chat since then ({@link #offer}), taken as the answer and not read as one more
     * message for the task. Messages offered before it are left for the task to read as before.
     *
     * @return the answer, or null when none came within {@code timeoutMs}, the task was stopped
     *         ({@code stopped}), or it takes no more messages
     */
    public synchronized UserMessage awaitAnswer(long timeoutMs, java.util.function.BooleanSupplier stopped)
            throws InterruptedException {
        asking = true;
        try {
            long end = System.currentTimeMillis() + timeoutMs;
            while (answer == null && taking && !stopped.getAsBoolean()) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                wait(Math.min(left, 1_000));
            }
            return answer;
        } finally {
            asking = false;
            answer = null;
        }
    }

    public synchronized void close(ObjLongConsumer<UserMessage> handOn) {
        taking = false;
        notifyAll();
        for (Sent unread : waiting) handOn.accept(unread.message(), unread.order());
        waiting.clear();
    }
}
