package com.reservation.config;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

//NOTE: @Primary tells springboot to use this as the SupabaseClient class over SupabaseOriginalClient
@Component
@Primary
public class SupabaseMockClient implements SupabaseClient{

    //Util for parsing JSON to match request
    private MockJSONParser parser;

    public SupabaseMockClient(MockJSONParser parser) {
        this.parser = parser;
    }

    public String get(String endpoint) {
        return parser.parse(endpoint);
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