package com.reservation.repositories;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.reservation.services.FileService;
import com.reservation.utils.Paths;

public class ReservationRepository {
    private final JsonNode databaseRoot;

    public ReservationRepository(FileService fileService) {
        JsonNode root = null;
        try {
            root = fileService.loadJson(Paths.MOCK_DATA_PATH);
        } catch (IOException e) {
            System.err.println("Could not load mock data");
            e.printStackTrace();
        }
        databaseRoot = root != null ? root : JsonNodeFactory.instance.objectNode();
    }

    public String getAllEvents() {
        return databaseRoot.path("events").toString();
    }

    public String getEventById(int id) {
        for (JsonNode node : databaseRoot.path("events")) {
            if (node.path("id").asInt() == id) {
                return node.toString();
            }
        }
        return "";
    }
}
