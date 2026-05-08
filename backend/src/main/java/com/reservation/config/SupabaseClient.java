package com.reservation.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.*;

//@Component is an annotation of spring that at start up creates an instance of this class that you can inject elsewhere
@Component
public class SupabaseClient {

    // @value is a spring annotation that injects value into field from application.properties
    /* The $ is a property placeholder telling spring that that this isnt just a string
    but a property to look for in the property sorce (application.properties)*/
    @Value("${supabase.url}")
    private String supabaseUrl;

    @Value("${supabase.api-key}")
    private String apiKey;

    private final HttpClient http = HttpClient.newHttpClient();

    // GET at /rest/v1/{endpoint}
    public String get(String endpoint) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(supabaseUrl + "/rest/v1/" + endpoint))
                .header("apikey", apiKey)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .GET()
                .build();

            return http.send(request, HttpResponse.BodyHandlers.ofString()).body();

        } catch (Exception e) {
            throw new RuntimeException("Supabase request failed: " + e.getMessage());
        }
    }

    // POST at /rest/v1/{endpoint}
    public String post(String endpoint, String json) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(supabaseUrl + "/rest/v1/" + endpoint))
                .header("apikey", apiKey)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Prefer", "return=representation")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

            return http.send(request, HttpResponse.BodyHandlers.ofString()).body();

        } catch (Exception e) {
            throw new RuntimeException("Supabase request failed: " + e.getMessage());
        }
    }

    // PATCH at /rest/v1/{endpoint}
    public String patch(String endpoint, String json) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(supabaseUrl + "/rest/v1/" + endpoint))
                .header("apikey", apiKey)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Prefer", "return=representation")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
                .build();

            return http.send(request, HttpResponse.BodyHandlers.ofString()).body();

        } catch (Exception e) {
            throw new RuntimeException("Supabase request failed: " + e.getMessage());
        }
    }

    // DELETE at /rest/v1/{endpoint}
    public void delete(String endpoint) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(supabaseUrl + "/rest/v1/" + endpoint))
                .header("apikey", apiKey)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .DELETE()
                .build();

            http.send(request, HttpResponse.BodyHandlers.ofString());

        } catch (Exception e) {
            throw new RuntimeException("Supabase request failed: " + e.getMessage());
        }
    }
}
