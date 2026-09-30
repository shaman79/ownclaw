package com.ownclaw.skillrunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.*;

/**
 * An answer the chat is waiting for. The setup wizard asks its questions as system messages and
 * waits here for each reply; the web chat and Telegram hand a message to a waiting question
 * instead of starting a task with it, and Stop cancels the wait.
 * <p>
 * Named for the skills that once asked the user questions while they ran (need_input). No skill
 * can: a task asks the owner between steps (ask_user), and his answer starts the next task.
 */
@Service
public class SkillInteractionHandler {

    private static final Logger log = LoggerFactory.getLogger(SkillInteractionHandler.class);

    /**
     * How long a question waits for a human answer.
     *
     * Was 120 seconds, which is a machine timeout applied to a person: the prompt has to be
     * noticed, read, thought about and typed, and the user may not be looking at the tab. Ten
     * minutes is still bounded, so a forgotten prompt cannot hold a thread forever, but it no
     * longer punishes someone for making a cup of tea.
     */
    private static final int INPUT_TIMEOUT_SEC = 600;

    /**
     * Pending input requests, keyed by "userId:taskId".
     * Each entry is a CompletableFuture that will complete when the user responds.
     */
    private final Map<String, CompletableFuture<String>> pendingInputs = new ConcurrentHashMap<>();

    /**
     * Wait for the user's next message, emitting nothing: the caller -- the setup wizard -- has
     * shown its own question.
     *
     * @param userId ID of the user who should respond
     * @param taskId what is asking, so the reply is routed to it
     * @return the user's response text
     * @throws TimeoutException if the user doesn't respond within the timeout
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws ExecutionException if the wait was cancelled ({@link #cancelPending})
     */
    public String requestInputSilent(String userId, String taskId)
            throws TimeoutException, InterruptedException, ExecutionException {
        String key = userId + ":" + taskId;

        CompletableFuture<String> future = new CompletableFuture<>();
        pendingInputs.put(key, future);
        log.info("Awaiting user input: user={} taskId={}", userId, taskId);

        try {
            return future.get(INPUT_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("User input timed out after {}s for key={}", INPUT_TIMEOUT_SEC, key);
            throw e;
        } finally {
            pendingInputs.remove(key);
        }
    }

    /**
     * Provide the user's input response.
     * Called from the WebSocket and Telegram handlers when a message answers a waiting question.
     *
     * @param userId the responding user's ID
     * @param taskId the task ID (must match the pending request)
     * @param input  the user's input text
     * @return true if a pending request was found and completed, false otherwise
     */
    public boolean provideInput(String userId, String taskId, String input) {
        String key = userId + ":" + taskId;
        CompletableFuture<String> future = pendingInputs.get(key);

        if (future != null) {
            future.complete(input);
            log.info("User input received for key={}", key);
            return true;
        }

        // Try userId-only key (when taskId is not known by the client)
        String fallbackKey = findPendingKeyForUser(userId);
        if (fallbackKey != null) {
            CompletableFuture<String> fallbackFuture = pendingInputs.get(fallbackKey);
            if (fallbackFuture != null) {
                fallbackFuture.complete(input);
                log.info("User input received via fallback for key={}", fallbackKey);
                return true;
            }
        }

        log.warn("No pending input request for key={}", key);
        return false;
    }

    /**
     * Check if there's a pending input request for a user.
     */
    public boolean hasPending(String userId) {
        return pendingInputs.keySet().stream().anyMatch(k -> k.startsWith(userId + ":"));
    }

    /**
     * Cancel all pending input requests for a user (e.g., on disconnect).
     */
    public void cancelPending(String userId) {
        pendingInputs.entrySet().removeIf(entry -> {
            if (entry.getKey().startsWith(userId + ":")) {
                entry.getValue().completeExceptionally(
                        new CancellationException("User disconnected"));
                return true;
            }
            return false;
        });
    }

    private String findPendingKeyForUser(String userId) {
        return pendingInputs.keySet().stream()
                .filter(k -> k.startsWith(userId + ":"))
                .findFirst()
                .orElse(null);
    }
}
