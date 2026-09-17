package com.ownclaw.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the request path that access control must be decided on.
 * <p>
 * <b>Why this exists.</b> {@code HttpServletRequest.getRequestURI()} returns the URI
 * <em>exactly as sent</em>, still percent-encoded. The servlet container matches filter
 * url-patterns, and Spring routes to controllers, on the <em>decoded</em> path. A filter that
 * authorises on {@code getRequestURI()} therefore sees a different string than the one that
 * decides which handler runs — and any filter that passes a request through when its prefix
 * check fails is an authentication bypass:
 * <pre>
 *   GET /%61pi/settings     raw: "/%61pi/settings"  -> prefix check "/api/" fails -> passed through
 *                       decoded: "/api/settings"    -> Spring routes to SettingsController
 * </pre>
 * That reached every {@code /api/*} endpoint with no credentials at all. Fixed 2026-09-17.
 * <p>
 * {@code getServletPath()} and {@code getPathInfo()} are decoded and normalised by the
 * container, which is why they are used here instead. The result is normalised again so that
 * {@code //api/ops}, {@code /api/./ops} and {@code /api/x/../ops} cannot slip past a prefix
 * check either.
 */
public final class RequestPaths {

    private RequestPaths() {
    }

    /**
     * The decoded, normalised path to authorise on. Never returns {@code null}; on anything
     * unparseable it returns {@code "/"}, which no public prefix matches, so callers fail
     * closed rather than open.
     */
    public static String effectivePath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();

        String path = (servletPath == null ? "" : servletPath) + (pathInfo == null ? "" : pathInfo);
        if (path.isEmpty()) {
            // Defensive: if the container gives us nothing, fall back to the raw URI rather
            // than an empty string. A raw URI never matches a public prefix by accident.
            String uri = request.getRequestURI();
            path = uri == null ? "/" : uri;
        }
        return normalise(path);
    }

    /**
     * Collapses repeated slashes and resolves {@code .} and {@code ..} segments. A path that
     * tries to climb above the root collapses to {@code "/"} rather than escaping.
     */
    static String normalise(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        // Strip a path-parameter suffix such as ";jsessionid=..." on any segment.
        int semicolon = path.indexOf(';');
        if (semicolon >= 0) {
            path = path.substring(0, semicolon);
        }

        boolean trailingSlash = path.endsWith("/") && path.length() > 1;
        var segments = new java.util.ArrayDeque<String>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                segments.pollLast();
                continue;
            }
            segments.addLast(segment);
        }

        if (segments.isEmpty()) {
            return "/";
        }
        var sb = new StringBuilder();
        for (String segment : segments) {
            sb.append('/').append(segment);
        }
        if (trailingSlash) {
            sb.append('/');
        }
        return sb.toString();
    }
}
