package com.ownclaw.interfaces.web;

import com.ownclaw.agent.memory.CapturedFacts;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.function.Supplier;

/**
 * The facts the local model found in a chat turn: the Undo under a "Remembered" row in the chat,
 * and the Keep under a fact it found in an answer. Requires the caller's login (JwtAuthFilter),
 * and acts only on the caller's own.
 */
@RestController
@RequestMapping("/api/facts")
public class FactController {

    private static final Logger log = LoggerFactory.getLogger(FactController.class);

    private final CapturedFacts captured;

    public FactController(CapturedFacts captured) {
        this.captured = captured;
    }

    /**
     * Take back a fact kept after a turn: a new one is forgotten, a corrected one is what it was.
     * POST /api/facts/captures/{ref}/undo -> {"done": true|false, "text": what happened, in words}
     */
    @PostMapping("/captures/{ref}/undo")
    public ResponseEntity<?> undo(HttpServletRequest request, @PathVariable String ref) {
        String userId = (String) request.getAttribute("userId");
        if (userId == null) return ResponseEntity.status(401).build();
        return answer(() -> captured.undo(userId, ref), "undone");
    }

    /**
     * Keep a fact found in an answer, as the owner asks.
     * POST /api/facts/captures/{ref}/keep -> {"done": true|false, "text": what happened, in words}
     */
    @PostMapping("/captures/{ref}/keep")
    public ResponseEntity<?> keep(HttpServletRequest request, @PathVariable String ref) {
        String userId = (String) request.getAttribute("userId");
        if (userId == null) return ResponseEntity.status(401).build();
        return answer(() -> captured.keep(userId, ref), "kept");
    }

    /** What was done, in words -- or, when it could not be done, that: never an error page. */
    private static ResponseEntity<?> answer(Supplier<CapturedFacts.Done> act, String what) {
        CapturedFacts.Done done;
        try {
            done = act.get();
        } catch (RuntimeException e) {
            log.warn("A fact could not be {}: {}", what, e.getClass().getSimpleName());
            done = new CapturedFacts.Done(false, "It could not be " + what + " just now. Try again.");
        }
        return ResponseEntity.ok(Map.of("done", done.done(), "text", done.text()));
    }
}
