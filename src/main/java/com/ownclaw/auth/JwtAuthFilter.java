package com.ownclaw.auth;

import com.ownclaw.users.AuthService;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * Servlet filter that enforces JWT authentication on REST API endpoints.
 * Public paths (auth endpoints, health check, static resources, WebSocket) are excluded.
 */
public class JwtAuthFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    /** Paths that never require authentication. */
    private static final Set<String> PUBLIC_PREFIXES = Set.of(
            "/api/auth/",
            "/api/health",
            "/ws/"
    );

    private final AuthService authService;

    public JwtAuthFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String path = request.getRequestURI();

        // Skip public paths and static resources
        if (isPublic(path)) {
            chain.doFilter(req, res);
            return;
        }

        // Only protect /api/* paths — static resources (HTML, CSS, JS) pass through
        if (!path.startsWith("/api/")) {
            chain.doFilter(req, res);
            return;
        }

        // Extract Bearer token
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Authentication required\"}");
            return;
        }

        String token = authHeader.substring(7);
        Optional<String> userId = authService.validateToken(token);

        if (userId.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Invalid or expired token\"}");
            return;
        }

        // Attach userId to request for downstream use
        request.setAttribute("userId", userId.get());
        chain.doFilter(req, res);
    }

    private boolean isPublic(String path) {
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        // Exact match for /api/health (no trailing slash)
        return "/api/health".equals(path);
    }
}
