package io.vidocq.grimm.cdi;

import io.vidocq.cassini.chappe.ChappeHttpAdapter;
import io.vidocq.cassini.spi.http.CassiniStack;
import io.vidocq.chappe.api.Server;
import jakarta.ws.rs.core.Application;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real HTTP integration test through Chappe transport + Cassini JAX-RS stack.
 *
 * Spec §2.1/§2.2/§2.3: /openapi endpoint, content negotiation, and format override.
 */
class OpenApiHttpChappeTest {

    private static Server server;
    private static String baseUrl;
    private static HttpClient http;

    @BeforeAll
    static void start() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        var stack = CassiniStack.builder()
                .application(new Application() {
                    @Override
                    public Set<Class<?>> getClasses() {
                        return Set.of(OpenApiResource.class);
                    }
                })
                .build();

        server = Server.builder()
                .host("127.0.0.1")
                .port(port)
                .handler(new ChappeHttpAdapter(stack.adapter()))
                .build();
        server.start();

        baseUrl = "http://127.0.0.1:" + port;
        http = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void httpOpenApi_defaultsToYaml() throws Exception {
        var response = http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/openapi"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("openapi:"));
        assertTrue(response.headers().firstValue("content-type")
                .map(v -> v.startsWith("application/yaml"))
                .orElse(false));
    }

    @Test
    void httpOpenApi_formatOverridesAccept() throws Exception {
        var response = http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/openapi?format=json"))
                        .header("Accept", "application/yaml")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"openapi\""));
        assertTrue(response.headers().firstValue("content-type")
                .map(v -> v.startsWith("application/json"))
                .orElse(false));
    }
}


