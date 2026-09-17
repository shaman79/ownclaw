package com.ownclaw.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Adds baseline security response headers.
 * <p>
 * The agent quotes web pages and e-mail it fetched into chat messages that are persisted and
 * re-rendered, so the client is an XSS target by design. The renderer sanitises what it
 * inserts; these headers are the second layer, and they matter most for what an injected
 * script could <em>do</em>:
 * <ul>
 *   <li>{@code connect-src 'self'} — no exfiltration to an attacker's server.</li>
 *   <li>{@code img-src 'self' data:} — no beacon via a remote image URL.</li>
 *   <li>{@code object-src 'none'}, {@code base-uri 'self'}, {@code frame-ancestors 'none'} —
 *       no plugin content, no base-tag hijack, no framing.</li>
 * </ul>
 * <b>Known weakness, stated plainly:</b> {@code index.html} carries its whole application in
 * one inline {@code <script>} and one inline {@code <style>}, so the policy has to allow
 * {@code 'unsafe-inline'} for scripts. That means the CSP does not stop injected inline
 * script — the renderer's sanitiser is what does. Moving the client to real module files (it
 * is one 4.8k-line page today) is what would let this become a nonce policy and actually
 * block injected script; it is part of the planned UI split.
 */
public class SecurityHeadersFilter implements Filter {

    private static final String CDN = "https://cdnjs.cloudflare.com";

    private static final String CSP = String.join("; ",
            "default-src 'self'",
            // 'unsafe-inline' is required by the single inline application script - see above.
            "script-src 'self' 'unsafe-inline' " + CDN,
            "style-src 'self' 'unsafe-inline' " + CDN,
            "img-src 'self' data:",
            "font-src 'self' data:",
            // Same-origin XHR plus the chat WebSocket. No other destination is reachable.
            "connect-src 'self' ws: wss:",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        if (res instanceof HttpServletResponse response) {
            response.setHeader("Content-Security-Policy", CSP);
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Referrer-Policy", "no-referrer");
            response.setHeader("X-Frame-Options", "DENY");
            response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
            // Nothing here should ever be cached by an intermediary.
            if (req instanceof HttpServletRequest request
                    && request.getRequestURI() != null
                    && request.getRequestURI().startsWith("/api/")) {
                response.setHeader("Cache-Control", "no-store");
            }
        }
        chain.doFilter(req, res);
    }
}
