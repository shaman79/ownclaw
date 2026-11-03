package com.ownclaw.skillrunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.*;

/**
 * An answer the chat is waiting for. The setup wizard asks its questions as system messages and
 * waits here for each reply; the web chat and Telegram hand a message to a waiting question
 * instead of starting a task with it, and Stop or /cancel cancels the wait.
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
     * The question each user's next message answers, by user. One waits at a time -- the wizard
     * asks none while another waits -- so a user's message is its answer. Keyed by what was
     * asking too, it was found by the user all the same: neither the page nor Telegram knows
     * what is asking.
     */
    private final Map<String, CompletableFuture<String>> pendingInputs = new ConcurrentHashMap<>();

    /**
     * Wait for the user's next message, emitting nothing: the caller -- the setup wizard -- has
     * shown its own question.
     *
     * @param userId ID of the user who should respond
     * @return the user's response text
     * @throws TimeoutException if the user doesn't respond within the timeout
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws ExecutionException if the wait was cancelled ({@link #cancelPending})
     */
    public String requestInputSilent(String userId)
            throws TimeoutException, InterruptedException, ExecutionException {
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingInputs.put(userId, future);
        log.info("Awaiting user input: user={}", userId);

        try {
            return future.get(INPUT_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("User input timed out after {}s for user={}", INPUT_TIMEOUT_SEC, userId);
            throw e;
        } finally {
            pendingInputs.remove(userId, future);
        }
    }

    /**
     * Provide the user's input response.
     * Called from the WebSocket and Telegram handlers when a message answers a waiting question.
     *
     * @param userId the responding user's ID
     * @param input  the user's input text
     * @return true if a pending request was found and completed, false otherwise
     */
    public boolean provideInput(String userId, String input) {
        CompletableFuture<String> future = pendingInputs.get(userId);
        if (future == null) {
            log.warn("No pending input request for user={}", userId);
            return false;
        }
        future.complete(input);
        log.info("User input received for user={}", userId);
        return true;
    }

    /**
     * Check if there's a pending input request for a user.
     */
    public boolean hasPending(String userId) {
        return pendingInputs.containsKey(userId);
    }

    /**
     * Cancel a user's pending input request (e.g., on disconnect).
     * <p>
     * Ended as a failure, not as a cancellation: {@code get()} throws a CancellationException as
     * it is, past the waiting wizard's catch of ExecutionException, and the wizard ended without
     * a word.
     */
    public void cancelPending(String userId) {
        CompletableFuture<String> future = pendingInputs.remove(userId);
        if (future != null) future.completeExceptionally(new IllegalStateException("the question was cancelled"));
    }
}
