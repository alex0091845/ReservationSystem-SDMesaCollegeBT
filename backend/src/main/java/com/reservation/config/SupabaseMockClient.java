package com.reservation.config;

import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

//NOTE: @Primary tells springboot to use this as the SupabaseClient class over SupabaseOriginalClient
@Component
@Primary
public class SupabaseMockClient {

    private final ResourceLoader resourceLoader;

    // Spring injects the ResourceLoader automatically
    public SupabaseMockClient(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    /**
     * Helper class to read JSON data
     */
    private String getMockData() {
        try {
            Resource resource = resourceLoader.getResource("classpath:mock_data.json");
            return FileCopyUtils.copyToString(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return "[]"; // Return empty array if file is missing
        }
    }

    public String get(String endpoint) {
        return getMockData();
    }

    public String post(String endpoint, String json) {
        // Return the sent JSON to simulate a successful save/return=representation
        return json;
    }

    public String patch(String endpoint, String json) {
        return json;
    }

    public void delete(String endpoint) {
        // Do nothing
    }
}