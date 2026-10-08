package com.reservation.services;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.reservation.config.SupabaseClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
public class AuthService {
    private final SupabaseClient supabase;
    private final PasswordEncoder encoder;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuthService(SupabaseClient supabase, PasswordEncoder encoder) {
        this.supabase = supabase;
        this.encoder = encoder;
    }

    public JsonNode authenticate(String email, String password) {
        try {
            if (email == null || password == null || password.getBytes(StandardCharsets.UTF_8).length > 72) {
                return null;
            }

            JsonNode user = findUserByEmail(email.trim().toLowerCase(Locale.ROOT));

            if (user == null || !user.path("enabled").asBoolean(false) ||
                !passwordMatches(password, user.path("password_hash").asText(""))) {
                return null;
            }

            return sanitizeUser(user);
        } catch (Exception e) {
            throw new RuntimeException("Login failed");
        }
    }

    public JsonNode findUserById(int id) {
        try {
            String response = supabase.get(
                "users?id=eq." + id +
                    "&select=id,email,first_name,last_name,phone,role_name,enabled,user_roles(name)"
            );
            JsonNode users = mapper.readTree(response);

            if (!users.isArray() || users.isEmpty()) {
                return null;
            }

            JsonNode user = users.get(0);
            return user.path("enabled").asBoolean(false) ? sanitizeUser(user) : null;
        } catch (Exception e) {
            throw new RuntimeException("Could not load session user");
        }
    }

    public boolean isAdmin(JsonNode user) {
        return "admin".equalsIgnoreCase(getRoleName(user));
    }

    public boolean isFacultyOrAdmin(JsonNode user) {
        String roleName = getRoleName(user);
        return "admin".equalsIgnoreCase(roleName) || "faculty".equalsIgnoreCase(roleName);
    }

    private JsonNode findUserByEmail(String email) throws Exception {
        String encodedEmail = URLEncoder.encode(email, StandardCharsets.UTF_8);
        String response = supabase.get(
            "users?email=eq." + encodedEmail +
                "&select=id,email,password_hash,first_name,last_name,phone,role_name,enabled,user_roles(name)"
        );
        JsonNode users = mapper.readTree(response);

        if (!users.isArray() || users.isEmpty()) {
            return null;
        }

        return users.get(0);
    }

    private boolean passwordMatches(String rawPassword, String storedPassword) {
        if (rawPassword == null || rawPassword.getBytes(StandardCharsets.UTF_8).length > 72 ||
            storedPassword == null || storedPassword.isBlank()) {
            return false;
        }

        if (storedPassword.startsWith("$2a$") ||
            storedPassword.startsWith("$2b$") ||
            storedPassword.startsWith("$2y$")) {
            return encoder.matches(rawPassword, storedPassword);
        }

        // Fail closed: a stored value that is not a BCrypt hash is never a match.
        return false;
    }

    private JsonNode sanitizeUser(JsonNode user) {
        ObjectNode sanitizedUser = (ObjectNode) user.deepCopy();
        String roleName = getRoleName(sanitizedUser);

        sanitizedUser.remove("password_hash");

        if (!roleName.isBlank()) {
            sanitizedUser.put("role_name", roleName);
        }

        return sanitizedUser;
    }

    private String getRoleName(JsonNode user) {
        if (user == null || user.isMissingNode() || user.isNull()) {
            return "";
        }

        String roleName = user.path("role_name").asText("");

        if (!roleName.isBlank()) {
            return roleName;
        }

        roleName = user.path("role").asText("");

        if (!roleName.isBlank()) {
            return roleName;
        }

        return user.path("user_roles").path("name").asText("");
    }
}
