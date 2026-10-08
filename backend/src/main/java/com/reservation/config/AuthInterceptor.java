package com.reservation.config;

import tools.jackson.databind.JsonNode;
import com.reservation.services.AuthService;
import com.reservation.services.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    private final SessionService sessionService;
    private final AuthService authService;
    private final Set<String> allowedOrigins;

    public AuthInterceptor(
        SessionService sessionService,
        AuthService authService,
        @Value("${app.cors.allowed-origin-patterns}") String[] configuredOrigins
    ) {
        this.sessionService = sessionService;
        this.authService = authService;
        this.allowedOrigins = Arrays.stream(configuredOrigins)
            .map(String::trim)
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
        throws IOException {
        String method = request.getMethod();
        String path = request.getRequestURI();

        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");

        if (!path.startsWith("/api/") || "OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }

        if (isCrossSiteMutation(request)) {
            writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, "Cross-site request rejected");
            return false;
        }

        if (isPublicEndpoint(method, path)) {
            if (isEventReadEndpoint(method, path)) {
                JsonNode currentUser = sessionService.getCurrentUser(request);
                if (currentUser != null) {
                    request.setAttribute("currentUser", currentUser);
                }
            }
            return true;
        }

        JsonNode currentUser = sessionService.getCurrentUser(request);

        if (currentUser == null) {
            writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
            return false;
        }

        request.setAttribute("currentUser", currentUser);

        if (requiresAdmin(method, path) && !authService.isAdmin(currentUser)) {
            writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, "Admin access required");
            return false;
        }

        if (requiresFacultyOrAdmin(method, path) && !authService.isFacultyOrAdmin(currentUser)) {
            writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, "Faculty or admin access required");
            return false;
        }

        return true;
    }

    private boolean isPublicEndpoint(String method, String path) {
        if (path.equals("/api/login") ||
            path.equals("/api/session") ||
            path.equals("/api/logout") ||
            path.equals("/api/health")) {
            return true;
        }

        if ("GET".equalsIgnoreCase(method) &&
            (isAtOrBelow(path, "/api/events") ||
             isAtOrBelow(path, "/api/event-types") ||
             isAtOrBelow(path, "/api/roles"))) {
            return true;
        }

        return "POST".equalsIgnoreCase(method) && path.equals("/api/attendees");
    }

    private boolean requiresAdmin(String method, String path) {
        if (isAtOrBelow(path, "/api/users")) {
            return true;
        }

        if ("GET".equalsIgnoreCase(method)) {
            return false;
        }

        return isAtOrBelow(path, "/api/roles") ||
            isAtOrBelow(path, "/api/event-types");
    }

    private boolean requiresFacultyOrAdmin(String method, String path) {
        return isAtOrBelow(path, "/api/attendees");
    }

    private boolean isEventReadEndpoint(String method, String path) {
        return "GET".equalsIgnoreCase(method) && isAtOrBelow(path, "/api/events");
    }

    private boolean isAtOrBelow(String path, String basePath) {
        return path.equals(basePath) || path.startsWith(basePath + "/");
    }

    private boolean isCrossSiteMutation(HttpServletRequest request) {
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
            return false;
        }

        String origin = request.getHeader("Origin");
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        return origin != null && !allowedOrigins.contains(origin) ||
            "cross-site".equalsIgnoreCase(fetchSite);
    }

    private void writeJsonError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
