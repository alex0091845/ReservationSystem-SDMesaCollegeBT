package com.reservation.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.reservation.services.AuthService;
import com.reservation.services.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    private final SessionService sessionService;
    private final AuthService authService;

    public AuthInterceptor(SessionService sessionService, AuthService authService) {
        this.sessionService = sessionService;
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
        throws IOException {
        String method = request.getMethod();
        String path = request.getRequestURI();

        if (!path.startsWith("/api/") || "OPTIONS".equalsIgnoreCase(method) || isPublicEndpoint(method, path)) {
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
            (path.startsWith("/api/events") ||
             path.startsWith("/api/event-types") ||
             path.startsWith("/api/roles"))) {
            return true;
        }

        return "POST".equalsIgnoreCase(method) && path.equals("/api/attendees");
    }

    private boolean requiresAdmin(String method, String path) {
        if (path.startsWith("/api/users")) {
            return true;
        }

        if ("GET".equalsIgnoreCase(method)) {
            return false;
        }

        return path.startsWith("/api/roles") ||
            path.startsWith("/api/event-types");
    }

    private void writeJsonError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
