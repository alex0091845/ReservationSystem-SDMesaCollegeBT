package com.reservation.services;

import java.io.File;
import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class FileService {
    public FileService() {

    }

    public JsonNode loadJson(String filepath) throws IOException
    {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readTree(new File(filepath));
    }
}
