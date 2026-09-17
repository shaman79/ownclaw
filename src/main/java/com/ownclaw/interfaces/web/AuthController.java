package com.ownclaw.interfaces.web;

import com.ownclaw.users.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * REST controller for authentication: register and login.
 * Returns JWT tokens for WebSocket authentication.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Auth status — tells the frontend whether to show register or login.
     * Also validates an existing token if provided.
     * GET /api/auth/status
     */
    @GetMapping("/status")
    public ResponseEntity<?> status(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        boolean hasUsers = authService.hasRegisteredUsers();
        var userId = bearerUserId(authHeader);
        boolean authenticated = userId.isPresent();

        return ResponseEntity.ok(Map.of(
                "hasUsers", hasUsers,
                "authenticated", authenticated,
                // Self-registration exists only for first-run setup (see AuthService.register)
                "registrationOpen", !hasUsers,
                "owner", authService.isOwner(userId.orElse(null))
        ));
    }

    private Optional<String> bearerUserId(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authService.validateToken(authHeader.substring(7));
        }
        return Optional.empty();
    }

    /**
     * Register a new user.
     * POST /api/auth/register
     * Body: {"username": "...", "password": "..."}
     * Returns: {"token": "jwt...", "username": "..."} for the first account (first-run setup).
     * Once an account exists this needs the owner's Bearer token and returns no token,
     * so that adding an account for someone else does not sign the owner out of theirs.
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                      @RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        if (username == null || username.isBlank() || password == null || password.length() < 4) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Username required, password must be at least 4 characters"));
        }

        String requesterId = bearerUserId(authHeader).orElse(null);
        try {
            String token = authService.register(username.trim(), password, requesterId);
            if (requesterId != null) {
                return ResponseEntity.ok(Map.of("username", username.trim(), "created", true));
            }
            return ResponseEntity.ok(Map.of("token", token, "username", username.trim()));
        } catch (AuthService.RegistrationClosedException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Login with existing credentials.
     * POST /api/auth/login
     * Body: {"username": "...", "password": "..."}
     * Returns: {"token": "jwt..."}
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Username and password required"));
        }

        try {
            String token = authService.login(username.trim(), password);
            return ResponseEntity.ok(Map.of("token", token, "username", username.trim()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }
}
