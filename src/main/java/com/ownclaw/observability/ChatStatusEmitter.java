package com.ownclaw.observability;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Emits structured status messages to user chat sessions.
 * Components push status messages here; interfaces (Telegram, WebSocket) subscribe per user.
 */
@Service
public class ChatStatusEmitter {

    /** Registered listeners per user. Each consumer receives a formatted status string. */
    private final Map<String, Consumer<StatusMessage>> listeners = new ConcurrentHashMap<>();

    /**
     * Register a listener for a user's status messages.
     * Only one listener per user (latest wins — covers Telegram or WebUI, not both simultaneously in Phase 1).
     */
    public void subscribe(String userId, Consumer<StatusMessage> listener) {
        listeners.put(userId, listener);
    }

    public void unsubscribe(String userId) {
        listeners.remove(userId);
    }

    /**
     * Emit a status message to the user's active chat interface.
     */
    public void emit(String userId, StatusMessage message) {
        Consumer<StatusMessage> listener = listeners.get(userId);
        if (listener != null) {
            listener.accept(message);
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

    /**
     * A structured status message sent to the user's chat.
     *
     * @param type message category
     * @param text human-readable text
     * @param data optional structured data (token counts, provider info, etc.)
     */
    public record StatusMessage(Type type, String text, Map<String, Object> data) {

        /** Construct without data. */
        public StatusMessage(Type type, String text) {
            this(type, text, null);
        }

        public enum Type {
            QUEUED, STARTED, STEP, PROGRESS, NEED_INPUT, CREDENTIAL,
            MENTOR, COMPLETED, FAILED, ROLLBACK, WARNING, DEBUG, SCHEDULED
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
            };
        }
    }
}
