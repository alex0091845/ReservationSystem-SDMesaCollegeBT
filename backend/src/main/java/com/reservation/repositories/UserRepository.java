package com.reservation.repositories;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reservation.services.FileService;
import com.reservation.utils.Paths;

public class UserRepository {
    private final JsonNode databaseRoot;

    public UserRepository(FileService fileService) {
        JsonNode root = null;
        try {
            root = fileService.loadJson(Paths.MOCK_DATA_PATH);
        } catch (IOException e) {
            System.err.println("Could not load mock data");
            e.printStackTrace();
        }
        databaseRoot = root != null ? root : JsonNodeFactory.instance.objectNode();
    }

    public String getAllUsers() {
        return enrichAllUsers().toString();
    }

    public String getUserById(int id) {
        for (JsonNode node : databaseRoot.path("users")) {
            if (node.path("user_id").asInt() == id) {
                return enrichUser(node).toString();
            }
        }
        return "";
    }

    public JsonNode getUserByEmail(String email) {
        for (JsonNode node : databaseRoot.path("users")) {
            if (node.path("email").asText().equals(email)) {
                return enrichUser(node);
            }
        }
        return null;
    }

    private ArrayNode enrichAllUsers() {
        ArrayNode enriched = JsonNodeFactory.instance.arrayNode();
        for (JsonNode user : databaseRoot.path("users")) {
            enriched.add(enrichUser(user));
        }
        return enriched;
    }

    private ObjectNode enrichUser(JsonNode user) {
        ObjectNode copy = user.deepCopy();
        int roleId = user.path("role_id").asInt();
        for (JsonNode role : databaseRoot.path("user_roles")) {
            if (role.path("role_id").asInt() == roleId) {
                ObjectNode roleEmbed = JsonNodeFactory.instance.objectNode();
                roleEmbed.put("name", role.path("name").asText());
                copy.set("user_roles", roleEmbed);
                break;
            }
        }
        return copy;
    }
}
