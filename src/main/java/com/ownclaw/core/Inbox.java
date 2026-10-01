package com.ownclaw.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The messages sent to one running task in its chat: offered while it runs, read by the task
 * before each step, and handed on as tasks of their own once it has ended. One lock over the
 * three, so each message is read by the task or handed on -- never both, and never neither: an
 * offer that comes after the close is refused, and its caller queues the message itself.
 */
public final class Inbox {

    private final List<UserMessage> waiting = new ArrayList<>();
    private boolean closed;

    /**
     * Take a message for the task.
     *
     * @return false when the task has ended: the message is then the caller's to queue
     */
    public synchronized boolean offer(UserMessage message) {
        if (closed) return false;
        waiting.add(message);
        return true;
    }

    /** The messages offered since the task last read, in the order they came: the task reads them now. */
    public synchronized List<UserMessage> drain() {
        List<UserMessage> read = List.copyOf(waiting);
        waiting.clear();
        return read;
    }

    /**
     * The task has ended: from now on no message is taken, and each one it did not read is handed
     * to {@code handOn}, in order, before this returns -- so before any offer refused from here
     * on, whose caller queues its message after these.
     *
     * @return the messages handed on
     */
    public synchronized List<UserMessage> close(Consumer<UserMessage> handOn) {
        closed = true;
        List<UserMessage> unread = drain();
        unread.forEach(handOn);
        return unread;
    }
}
