package com.reservation.repositories;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.reservation.services.FileService;
import com.reservation.utils.Paths;

public class AttendeeRepository {
    private final FileService fileService;
    private JsonNode databaseRoot;

    public AttendeeRepository(FileService fileService) {
        this.fileService = fileService;

        try
        {
            databaseRoot = this.fileService.loadJson(Paths.MOCK_DATA_PATH);
        }
        catch (IOException e)
        {
            System.err.println("Could not load mock data");
            e.printStackTrace();
        }
    }

    public String getAllAttendees()
    {
        return databaseRoot.get("attendees").toString();
    }

    public String getAttendeeById(long id) {
        // possible lossy get id here. Shouldn't be a problem if the web server
        // just returns whatever the database returns down the line
        for (JsonNode node : databaseRoot.get("attendees"))
        {
            if (node.get("id").asLong() == id)
            {
                return node.toString();
            }
        }

        return "";
    }

    // TODO
    
}
