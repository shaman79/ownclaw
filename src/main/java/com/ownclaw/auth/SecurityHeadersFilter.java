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

    /**
     * Everything except connect-src, which depends on the request (see {@link #cspFor}).
     */
    private static final String CSP_PREFIX = String.join("; ",
            "default-src 'self'",
            // 'unsafe-inline' is required by the single inline application script - see above.
            "script-src 'self' 'unsafe-inline' " + CDN,
            "style-src 'self' 'unsafe-inline' " + CDN,
            "img-src 'self' data:",
            "font-src 'self' data:");

    private static final String CSP_SUFFIX = String.join("; ",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    /** Host header shapes we will echo into a CSP. Anything else falls back to 'self' only. */
    private static final java.util.regex.Pattern SAFE_HOST =
            java.util.regex.Pattern.compile("[A-Za-z0-9.\\-]{1,253}(:[0-9]{1,5})?");

    /**
     * Build the policy for this request, pinning the WebSocket to this exact host.
     * <p>
     * connect-src used to read {@code 'self' ws: wss:}. Those two are SCHEMES, not origins:
     * they permit a WebSocket to any host on the internet. So the one directive whose job was
     * to stop an injected script exfiltrating data allowed exactly that, while this class's own
     * javadoc claimed "no exfiltration to an attacker's server".
     * <p>
     * The reason it was written that way is real: the client opens {@code ws://<this host>/ws},
     * and CSP has no "same origin, other scheme" keyword. {@code 'self'} does cover same-origin
     * WebSockets under CSP Level 3, but relying on that silently breaks chat on any browser
     * that disagrees, and the failure would look like a connection problem rather than a policy
     * one. Naming the host explicitly works everywhere and needs no such bet.
     * <p>
     * The Host header is attacker-controllable in principle, so it is validated against a
     * hostname shape before being echoed, and dropped entirely if it does not match. A bad Host
     * therefore yields a STRICTER policy, never a weaker one.
     */
    static String cspFor(HttpServletRequest request) {
        String connect = "connect-src 'self'";
        String host = request == null ? null : request.getHeader("Host");
        if (host != null && SAFE_HOST.matcher(host).matches()) {
            connect += " ws://" + host + " wss://" + host;
        }
        return CSP_PREFIX + "; " + connect + "; " + CSP_SUFFIX;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        if (res instanceof HttpServletResponse response) {
            response.setHeader("Content-Security-Policy",
                    cspFor(req instanceof HttpServletRequest hr ? hr : null));
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
