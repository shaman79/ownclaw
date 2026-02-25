package com.ownclaw.skillrunner;

import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.*;

/**
 * Manages mid-execution user interaction for skills that emit need_input.
 * When a skill requests input, the handler:
 * 1. Emits a NEED_INPUT status message to the user's chat
 * 2. Blocks the skill's execution on a CompletableFuture
 * 3. Receives the user's response from the chat interface
 * 4. Completes the future, allowing the skill process to continue
 */
@Service
public class SkillInteractionHandler {

    private static final Logger log = LoggerFactory.getLogger(SkillInteractionHandler.class);

    /** Timeout for waiting for user input (seconds). */
    private static final int INPUT_TIMEOUT_SEC = 120;

    private final ChatStatusEmitter statusEmitter;

    /**
     * Pending input requests, keyed by "userId:taskId".
     * Each entry is a CompletableFuture that will complete when the user responds.
     */
    private final Map<String, CompletableFuture<String>> pendingInputs = new ConcurrentHashMap<>();

    public SkillInteractionHandler(ChatStatusEmitter statusEmitter) {
        this.statusEmitter = statusEmitter;
    }

    /**
     * Request input from the user without emitting a NEED_INPUT status message.
     *
     * Useful for flows (like /setup) that render their own prompts as system messages.
     */
    public String requestInputSilent(String userId, String taskId)
            throws TimeoutException, InterruptedException, ExecutionException {
        return requestInputInternal(userId, taskId, null, false);
    }

    /**
     * Request input from the user during skill execution.
     * Emits a NEED_INPUT status message and blocks until the user responds or timeout.
     *
     * @param userId ID of the user who should respond
     * @param taskId current task ID (for routing)
     * @param prompt the prompt to show the user
     * @return the user's response text
     * @throws TimeoutException if the user doesn't respond within the timeout
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public String requestInput(String userId, String taskId, String prompt)
            throws TimeoutException, InterruptedException, ExecutionException {

        return requestInputInternal(userId, taskId, prompt, true);
    }

    private String requestInputInternal(String userId, String taskId, String prompt, boolean emitStatus)
            throws TimeoutException, InterruptedException, ExecutionException {

        String key = userId + ":" + taskId;

        CompletableFuture<String> future = new CompletableFuture<>();
        pendingInputs.put(key, future);

        if (emitStatus && prompt != null && !prompt.isBlank()) {
            log.info("Skill requesting input from user={}: {}", userId, prompt);
            statusEmitter.emit(userId, StatusMessage.Type.NEED_INPUT, prompt);
        } else {
            log.info("Awaiting user input: user={} taskId={}", userId, taskId);
        }

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
     * Called from WebSocket/Telegram handler when the user responds to a need_input prompt.
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
