package com.ownclaw.auth;

import com.ownclaw.config.OwnClawConfig;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Authenticates the ops API ({@code /api/ops/*}) with a shared secret.
 * <p>
 * The token comes from the environment only ({@code OWNCLAW_OPS_TOKEN} via
 * {@code ownclaw.ops.token}) and is never written to the database, so dumping the database
 * does not disclose it and the ops API cannot be enabled by changing a stored setting.
 * <p>
 * Fails closed: if the token is unset or shorter than {@link #MIN_TOKEN_LENGTH}, every ops
 * request is refused with 503 and no endpoint is reachable. This filter runs before
 * {@link JwtAuthFilter}, which lists {@code /api/ops/} as JWT-exempt — so if this filter is
 * ever not registered, ops requests fall through to JwtAuthFilter and are rejected as
 * unauthenticated rather than served.
 */
public class OpsAuthFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(OpsAuthFilter.class);

    /** Short tokens are refused outright rather than guarded by rate limiting. */
    public static final int MIN_TOKEN_LENGTH = 32;

    private static final String PREFIX = "/api/ops/";

    private final byte[] expected;
    private final boolean enabled;

    public OpsAuthFilter(OwnClawConfig config) {
        String token = config.getOps().getToken();
        token = token == null ? "" : token.trim();
        this.enabled = token.length() >= MIN_TOKEN_LENGTH;
        this.expected = token.getBytes(StandardCharsets.UTF_8);

        if (enabled) {
            log.info("Ops API enabled at {}* (token {} chars)", PREFIX, token.length());
        } else if (token.isEmpty()) {
            log.info("Ops API disabled — set OWNCLAW_OPS_TOKEN (at least {} chars) to enable it",
                    MIN_TOKEN_LENGTH);
        } else {
            log.warn("Ops API disabled — OWNCLAW_OPS_TOKEN is only {} chars, minimum is {}",
                    token.length(), MIN_TOKEN_LENGTH);
        }
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        if (!isOpsPath(request.getRequestURI())) {
            chain.doFilter(req, res);
            return;
        }

        if (!enabled) {
            deny(response, 503, "Ops API is disabled on this instance");
            return;
        }

        if (!matches(presentedToken(request))) {
            // Log the attempt but never the presented value.
            log.warn("Ops API rejected: {} {} from {}", request.getMethod(),
                    request.getRequestURI(), clientAddress(request));
            deny(response, 401, "Valid ops token required");
            return;
        }

        // Every accepted ops call is audited: these endpoints read internal state.
        log.info("Ops API: {} {}{} from {}", request.getMethod(), request.getRequestURI(),
                request.getQueryString() == null ? "" : "?" + request.getQueryString(),
                clientAddress(request));
        chain.doFilter(req, res);
    }

    /** The bare index path counts as ops too, not just {@code /api/ops/...}. */
    static boolean isOpsPath(String path) {
        return path != null
                && (path.startsWith(PREFIX) || path.equals("/api/ops") || path.equals("/api/ops/"));
    }

    /** Accepts {@code X-Ops-Token: <token>} or {@code Authorization: Bearer <token>}. */
    private static String presentedToken(HttpServletRequest request) {
        String header = request.getHeader("X-Ops-Token");
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7).trim();
        }
        return "";
    }

    /** Constant-time comparison, so a wrong token leaks nothing through timing. */
    private boolean matches(String presented) {
        return MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8));
    }

    private static String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        // Advisory only — it is attacker-controlled and used for logs, never for access control.
        return forwarded != null && !forwarded.isBlank()
                ? request.getRemoteAddr() + " (xff " + forwarded.split(",")[0].trim() + ")"
                : request.getRemoteAddr();
    }

    private static void deny(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
