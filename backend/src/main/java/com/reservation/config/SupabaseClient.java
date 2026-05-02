package com.reservation.config;

/*
An interface for mock data purposes. 
Allows me to touch the OG Supabase client and the Controllers as little as possible
Both the OG client and the mock client extend supabase
*/
public interface SupabaseClient {
    String get(String endpoint);
    String post(String endpoint, String json);
    String patch(String endpoint, String json);
    void delete(String endpoint);
}
