package com.reservation.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SupabaseClientKeyHeaderTest {
    @Test
    void secretKeysAreSentAsApiKeyAndNotAsBearerTokens() throws Exception {
        AtomicReference<String> apiKey = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rest/v1/", exchange -> {
            apiKey.set(exchange.getRequestHeaders().getFirst("apikey"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            SupabaseClient client = new SupabaseClient();
            ReflectionTestUtils.setField(client, "supabaseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            ReflectionTestUtils.setField(client, "apiKey", "sb_secret_test-value");

            assertEquals("[]", client.get("users?select=id"));
            assertEquals("sb_secret_test-value", apiKey.get());
            assertNull(authorization.get());
        } finally {
            server.stop(0);
        }
    }
}
