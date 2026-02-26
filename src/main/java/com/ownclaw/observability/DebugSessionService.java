package com.ownclaw.observability;

import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which users have debug mode enabled.
 * Debug mode sends full thinking-engine prompts, raw LLM output,
 * critic verdicts, and tool results to the chat UI.
 */
@Service
public class DebugSessionService {

    private final Set<String> debugUsers = ConcurrentHashMap.newKeySet();

    /** Toggle debug mode for a user. Returns the new state. */
    public boolean toggle(String userId) {
        if (debugUsers.contains(userId)) {
            debugUsers.remove(userId);
            return false;
        } else {
            debugUsers.add(userId);
            return true;
        }
    }

    /** Check whether debug is currently enabled for a user. */
    public boolean isEnabled(String userId) {
        return debugUsers.contains(userId);
    }
}
