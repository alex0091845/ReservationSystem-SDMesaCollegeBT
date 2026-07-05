package com.reservation.repositories;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.reservation.services.FileService;
import com.reservation.utils.Paths;

public class EventTypeRepository {
    private final JsonNode databaseRoot;

    public EventTypeRepository(FileService fileService) {
        JsonNode root = null;
        try {
            root = fileService.loadJson(Paths.MOCK_DATA_PATH);
        } catch (IOException e) {
            System.err.println("Could not load mock data");
            e.printStackTrace();
        }
        databaseRoot = root != null ? root : JsonNodeFactory.instance.objectNode();
    }

    public String getAllEventTypes() {
        return databaseRoot.path("event_types").toString();
    }

    public String getEventTypeByType(String eventType) {
        for (JsonNode node : databaseRoot.path("event_types")) {
            if (node.path("event_type").asText().equals(eventType)) {
                return node.toString();
            }
        }
        return "";
    }
}
