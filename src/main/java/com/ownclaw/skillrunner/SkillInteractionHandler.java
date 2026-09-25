package com.ownclaw.skillrunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.*;

/**
 * A prompt waiting for the user's typed answer: the /setup wizard's, whose thread blocks here
 * until the answer arrives from the web chat or Telegram, the user cancels, or the time is up.
 * (A skill cannot ask the user anything while it runs; see ARCHITECTURE.md §5.8.)
 */
@Service
public class SkillInteractionHandler {

    private static final Logger log = LoggerFactory.getLogger(SkillInteractionHandler.class);

    /**
     * How long a prompt waits for a human answer: the prompt has to be noticed, read, thought
     * about and typed, and the user may not be looking at the tab. Ten minutes is still bounded,
     * so a forgotten prompt cannot hold its thread forever.
     */
    private static final int INPUT_TIMEOUT_SEC = 600;

    /**
     * Pending input requests, keyed by "userId:taskId".
     * Each entry is a CompletableFuture that will complete when the user responds.
     */
    private final Map<String, CompletableFuture<String>> pendingInputs = new ConcurrentHashMap<>();

    /**
     * Wait for the user's answer to a prompt the caller has already shown -- the /setup wizard
     * renders its own prompts as system messages.
     *
     * @throws TimeoutException if the user doesn't respond within the timeout
     */
    public String awaitAnswer(String userId, String taskId)
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
     * Called from the WebSocket and Telegram handlers when the user answers a waiting prompt.
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
