package com.ownclaw.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Emits structured status messages to user chat sessions.
 * Components push status messages here; interfaces (Telegram, WebSocket) subscribe per user.
 */
@Service
public class ChatStatusEmitter {

    private static final Logger log = LoggerFactory.getLogger(ChatStatusEmitter.class);

    /**
     * Listeners per user, each under its own key.
     * <p>
     * This used to be one consumer per user, latest wins, with the javadoc noting it covered
     * "Telegram or WebUI, not both simultaneously". In practice that is not a limitation, it is
     * three bugs. Opening the web UI silently deafened Telegram. A second browser tab silenced
     * the first. And because unsubscribe removed whoever happened to be registered rather than
     * the caller's own listener, closing a STALE tab tore down the live session's status stream
     * — the task carried on running, invisibly.
     * <p>
     * Keying by subscriber means a caller can only ever remove its own listener, and every
     * attached interface sees every message. Which is what the user expects: a task started in
     * the browser should report to Telegram too, not instead.
     */
    private final Map<String, Map<Object, Consumer<StatusMessage>>> listeners = new ConcurrentHashMap<>();

    /**
     * The key in a status's data that marks it as unattended work's -- a scheduled run, /bg --
     * set to true. Such a status is about work nobody is waiting on in the chat on screen, so a
     * page shows it only in the pinned chat of scheduled results, and never as the working state
     * of the chat it has open. Unmarked, the steps of a morning run filled the activity strip of
     * whatever chat was open, and started its spinner.
     */
    public static final String BACKGROUND = "background";

    /** The ids of the unattended tasks running now: every status emitted for one is marked. */
    private final Set<String> background = ConcurrentHashMap.newKeySet();

    /**
     * Run an unattended task's work with every status emitted for its id marked
     * {@link #BACKGROUND}, from its first step to its ending; the mark goes with the work, however
     * the work ends.
     */
    public <T> T inBackground(String taskId, Supplier<T> work) {
        background.add(taskId);
        try {
            return work.get();
        } finally {
            background.remove(taskId);
        }
    }

    /**
     * Register a listener for a user's status messages.
     *
     * @param key a stable identity for this subscriber (a WebSocket session, a bot instance).
     *            Subscribing twice with the same key replaces that one listener and no other.
     */
    public void subscribe(String userId, Object key, Consumer<StatusMessage> listener) {
        listeners.computeIfAbsent(userId, u -> new ConcurrentHashMap<>()).put(key, listener);
    }

    /** Remove only this subscriber's listener, leaving any others attached. */
    public void unsubscribe(String userId, Object key) {
        Map<Object, Consumer<StatusMessage>> forUser = listeners.get(userId);
        if (forUser == null) return;
        forUser.remove(key);
        if (forUser.isEmpty()) listeners.remove(userId);
    }

    /** True when at least one interface is listening — i.e. somebody is watching. */
    public boolean hasListener(String userId) {
        Map<Object, Consumer<StatusMessage>> forUser = listeners.get(userId);
        return forUser != null && !forUser.isEmpty();
    }

    /**
     * Emit a status message to every attached interface for this user.
     * <p>
     * One listener throwing must not stop the others receiving the message: a dead WebSocket
     * should never be able to silence Telegram.
     */
    public void emit(String userId, StatusMessage message) {
        Map<Object, Consumer<StatusMessage>> forUser = listeners.get(userId);
        if (forUser == null) return;
        for (Map.Entry<Object, Consumer<StatusMessage>> e : forUser.entrySet()) {
            try {
                e.getValue().accept(message);
            } catch (Exception ex) {
                log.debug("Status listener {} failed for {}: {}", e.getKey(), userId, ex.toString());
            }
        }
    }

    /** Convenience overload — text only (no structured data). */
    public void emit(String userId, StatusMessage.Type type, String text) {
        emit(userId, new StatusMessage(type, text, null));
    }

    /** Convenience overload — text + structured data (e.g. token counts). */
    public void emit(String userId, StatusMessage.Type type, String text, Map<String, Object> data) {
        emit(userId, new StatusMessage(type, text, data));
    }

    /** Emit attributed to a specific task. Use this from anywhere inside a running task. */
    public void emitForTask(String userId, String taskId, StatusMessage.Type type, String text) {
        emitForTask(userId, taskId, type, text, null);
    }

    /**
     * Emit attributed to a specific task, with structured data -- marked {@link #BACKGROUND} when
     * that task is unattended work running now ({@link #inBackground}).
     */
    public void emitForTask(String userId, String taskId, StatusMessage.Type type, String text,
                            Map<String, Object> data) {
        if (taskId != null && background.contains(taskId)) {
            var marked = data == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<>(data);
            marked.put(BACKGROUND, true);
            data = marked;
        }
        emit(userId, new StatusMessage(type, text, data, taskId));
    }

    /**
     * A structured status message sent to the user's chat.
     *
     * @param type message category
     * @param text human-readable text
     * @param data optional structured data (token counts, provider info, etc.)
     */
    public record StatusMessage(Type type, String text, Map<String, Object> data, String taskId) {

        /**
         * Without a task id.
         * <p>
         * Kept because most status comes from places that have no task — a connection notice, a
         * setup step. Those are unambiguous precisely because there is only ever one of them.
         * Anything emitted from inside a running task should carry its id: with one worker
         * thread two tasks cannot overlap, so a missing id is invisible today, and the moment a
         * second lane exists their step streams interleave into one channel the UI cannot
         * separate.
         */
        public StatusMessage(Type type, String text, Map<String, Object> data) {
            this(type, text, data, null);
        }

        /** Construct without data. */
        public StatusMessage(Type type, String text) {
            this(type, text, null, null);
        }

        public enum Type {
            QUEUED, STARTED, STEP, PROGRESS, NEED_INPUT, CREDENTIAL,
            MENTOR, COMPLETED, FAILED, ROLLBACK, WARNING, DEBUG, SCHEDULED,
            /**
             * The answer itself, from work that finished while nobody was watching.
             * <p>
             * Every other type here is commentary about a task — queued, running, done. This one
             * is the task's output, which is why it is not decorated and why interfaces render it
             * as an ordinary assistant message rather than an entry in the activity strip. A
             * morning digest is not a status line.
             */
            RESULT,
            /**
             * A message in a running task's own chat about what it is doing -- a step, a tool
             * call of the local model, the local model's summary of a private result -- saved
             * there as a progress row ({@code TaskChat}). Shown in the chat, secondary to the
             * answer, not in the activity strip; Telegram gets it for a task that came from
             * Telegram. Carries the chat it was saved in, and the owner's text when only he may
             * read it, as a result does.
             */
            PROGRESS_MESSAGE
        }

        /** Format with icon prefix for display. */
        public String formatted() {
            return switch (type) {
                case QUEUED     -> "\uD83D\uDCCB " + text;  // 📋
                case STARTED    -> "⚙\uFE0F " + text;       // ⚙️
                case STEP       -> "→ " + text;
                case PROGRESS   -> "⏳ " + text;
                case NEED_INPUT -> "❓ " + text;
                case CREDENTIAL -> "\uD83D\uDD11 " + text;  // 🔑
                case MENTOR     -> "\uD83E\uDDE0 " + text;  // 🧠
                case COMPLETED  -> "✅ " + text;
                case FAILED     -> "❌ " + text;
                case ROLLBACK   -> "⏪ " + text;
                case WARNING    -> "⚠\uFE0F " + text;       // ⚠️
                case DEBUG      -> "\uD83D\uDC1B " + text;  // 🐛
                case SCHEDULED  -> "\uD83D\uDD54 " + text;  // 🕔
                case RESULT     -> text;                    // the answer, not a note about it
                case PROGRESS_MESSAGE -> text;              // a message of its own, written whole
            };
        }
    }
}
