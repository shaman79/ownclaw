package com.ownclaw.interfaces.web;

import com.ownclaw.users.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

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
     * Register a new user.
     * POST /api/auth/register
     * Body: {"username": "...", "password": "..."}
     * Returns: {"token": "jwt...", "userId": "..."}
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        if (username == null || username.isBlank() || password == null || password.length() < 4) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Username required, password must be at least 4 characters"));
        }

        try {
            String token = authService.register(username.trim(), password);
            return ResponseEntity.ok(Map.of("token", token, "username", username.trim()));
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
