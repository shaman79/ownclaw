package com.ownclaw.interfaces.web;

import com.ownclaw.users.AuthService;
import com.ownclaw.users.UserRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Account management for the owner (Settings → Accounts). Same operations as the
 * {@code /user} chat commands. Every endpoint answers 403 to anyone but the owner.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final AuthService authService;
    private final UserRepository userRepo;

    public UserController(AuthService authService, UserRepository userRepo) {
        this.authService = authService;
        this.userRepo = userRepo;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestAttribute("userId") String userId) {
        if (!authService.isOwner(userId)) return ownerOnly();

        List<Map<String, Object>> accounts = new ArrayList<>();
        for (Map<String, Object> u : userRepo.listUsers()) {
            String id = (String) u.get("id");
            boolean webLogin = ((Number) u.get("has_password")).intValue() != 0;
            Object telegramId = u.get("telegram_id");

            var account = new LinkedHashMap<String, Object>();
            account.put("id", id);
            account.put("username", u.get("display_name"));
            account.put("owner", authService.isOwner(id));
            account.put("webLogin", webLogin);
            account.put("telegramId", telegramId);
            account.put("disabled", !webLogin && telegramId == null);
            account.put("createdAt", u.get("created_at"));
            accounts.add(account);
        }
        return ResponseEntity.ok(accounts);
    }

    /** Body: {"username": "...", "password": "..."} */
    @PostMapping
    public ResponseEntity<?> create(@RequestAttribute("userId") String userId,
                                    @RequestBody Map<String, String> body) {
        if (!authService.isOwner(userId)) return ownerOnly();

        String username = body.get("username");
        String password = body.get("password");
        if (username == null || username.isBlank() || password == null || password.length() < 4) {
            return badRequest("Username required, password must be at least 4 characters");
        }
        try {
            authService.register(username.trim(), password, userId);
            return ResponseEntity.ok(Map.of("username", username.trim(), "created", true));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<?> disable(@RequestAttribute("userId") String userId, @PathVariable String id) {
        if (!authService.isOwner(userId)) return ownerOnly();
        try {
            authService.disableAccount(id);
            return ResponseEntity.ok(Map.of("disabled", true));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    /** Body: {"telegramId": 123456} to link, {"telegramId": null} to unlink. */
    @PutMapping("/{id}/telegram")
    public ResponseEntity<?> linkTelegram(@RequestAttribute("userId") String userId, @PathVariable String id,
                                          @RequestBody Map<String, Object> body) {
        if (!authService.isOwner(userId)) return ownerOnly();
        if (userRepo.findById(id).isEmpty()) return badRequest("No such account: " + id);

        Object raw = body.get("telegramId");
        Long telegramId = null;
        if (raw != null && !raw.toString().isBlank()) {
            try {
                telegramId = Long.parseLong(raw.toString().trim());
            } catch (NumberFormatException e) {
                return badRequest("The Telegram ID must be a number.");
            }
        }
        try {
            userRepo.linkTelegram(id, telegramId);
            return ResponseEntity.ok(Map.of("linked", telegramId != null));
        } catch (DataAccessException e) {
            return badRequest("That Telegram ID is already linked to another account.");
        }
    }

    private static ResponseEntity<?> ownerOnly() {
        return ResponseEntity.status(403).body(Map.of("error", "Only the owner can manage accounts."));
    }

    private static ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
