package com.ownclaw.core;

import com.ownclaw.agent.TaskChat;

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
    private final List<Sent> waiting = new ArrayList<>();
    private boolean taking = true;

    public Inbox(TaskChat.Channel channel) {
        this.channel = channel;
    }

    /**
     * Take a message for the task, unless it cannot take it: one from another channel, which the
     * task's answer would not reach, or one with files -- those become a task's private files
     * when it starts, and added to a running task they would make its later results private
     * part-way through, past the check a task holding files starts with
     * ({@code AgentLoop.stopWithoutLocalModel}).
     *
     * @param order its place in the queue, taken when it was sent ({@link TaskQueue#steer}): where
     *              it runs if the task ends without reading it
     * @return false when the task has ended or takes nothing more: the message is then the
     *         caller's to queue
     */
    public synchronized boolean offer(UserMessage message, long order) {
        if (message.channel() != channel || !message.attachmentIds().isEmpty()) taking = false;
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
    public synchronized void close(ObjLongConsumer<UserMessage> handOn) {
        taking = false;
        for (Sent unread : waiting) handOn.accept(unread.message(), unread.order());
        waiting.clear();
    }
}
