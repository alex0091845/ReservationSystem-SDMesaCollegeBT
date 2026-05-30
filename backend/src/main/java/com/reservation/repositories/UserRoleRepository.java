package com.reservation.repositories;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.reservation.services.FileService;
import com.reservation.utils.Paths;

public class UserRoleRepository {
    private final JsonNode databaseRoot;

    public UserRoleRepository(FileService fileService) {
        JsonNode root = null;
        try {
            root = fileService.loadJson(Paths.MOCK_DATA_PATH);
        } catch (IOException e) {
            System.err.println("Could not load mock data");
            e.printStackTrace();
        }
        databaseRoot = root != null ? root : JsonNodeFactory.instance.objectNode();
    }

    public String getAllUserRoles() {
        return databaseRoot.path("user_roles").toString();
    }

    public String getUserRoleById(int id) {
        for (JsonNode node : databaseRoot.path("user_roles")) {
            if (node.path("role_id").asInt() == id) {
                return node.toString();
            }
        }
        return "";
    }
}
