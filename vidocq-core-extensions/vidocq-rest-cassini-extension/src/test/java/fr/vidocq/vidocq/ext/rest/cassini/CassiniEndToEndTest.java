package fr.vidocq.vidocq.ext.rest.cassini;

import fr.vidocq.chappe.api.Server;
import fr.vidocq.vidocq.ext.rest.cassini.internal.CassiniRestBridge;
import fr.vidocq.vidocq.ext.rest.cassini.internal.Invoker;
import fr.vidocq.vidocq.ext.rest.cassini.internal.ResourceMethod;
import fr.vidocq.vidocq.ext.rest.cassini.internal.ResourceScanner;
import fr.vidocq.vidocq.ext.rest.cassini.internal.UriRouter;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests end-to-end M1 : démarre Chappe + CassiniRestBridge sans CDI (resolver
 * de beans injecté manuellement) et valide routing + marshalling minimal.
 */
class CassiniEndToEndTest {

    private Server server;
    private int port;

    @AfterEach
    void tearDown() { if (server != null) server.stop(); }

    @Path("/hello")
    public static class HelloResource {
        @GET
        public String hello() { return "Hello from Cassini!"; }
    }

    @Path("/api")
    public static class ApiResource {
        @GET
        @Path("/ping")
        @Produces("application/json")
        public String ping() { return "{\"pong\":true}"; }

        @POST
        @Path("/ping")
        public void log() { /* void → 204 */ }
    }

    @Test
    void getHelloReturnsPlainText() throws Exception {
        start(new HelloResource(), new ApiResource());

        HttpResponse<String> r = get("/hello");
        assertEquals(200, r.statusCode());
        assertEquals("Hello from Cassini!", r.body());
        assertTrue(r.headers().firstValue("Content-Type").orElse("")
                .startsWith("text/plain"));
    }

    @Test
    void getApiPingReturnsJson() throws Exception {
        start(new HelloResource(), new ApiResource());

        HttpResponse<String> r = get("/api/ping");
        assertEquals(200, r.statusCode());
        assertEquals("{\"pong\":true}", r.body());
        assertEquals("application/json",
                r.headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    void voidReturnYields204() throws Exception {
        start(new HelloResource(), new ApiResource());

        HttpResponse<String> r = post("/api/ping");
        assertEquals(204, r.statusCode());
    }

    @Test
    void unknownPathYields404() throws Exception {
        start(new HelloResource(), new ApiResource());

        HttpResponse<String> r = get("/nope");
        assertEquals(404, r.statusCode());
    }

    @Test
    void wrongMethodYields405WithAllow() throws Exception {
        start(new HelloResource(), new ApiResource());

        HttpResponse<String> r = post("/hello");
        assertEquals(405, r.statusCode());
        assertEquals(Optional.of("GET"), r.headers().firstValue("Allow"));
    }

    private void start(Object... resources) {
        Map<Class<?>, Object> beans = new HashMap<>();
        Class<?>[] classes = new Class<?>[resources.length];
        for (int i = 0; i < resources.length; i++) {
            beans.put(resources[i].getClass(), resources[i]);
            classes[i] = resources[i].getClass();
        }
        List<ResourceMethod> routes = ResourceScanner.discover(classes);
        UriRouter router = new UriRouter(routes);
        Invoker invoker = new Invoker(beans::get);
        CassiniRestBridge bridge = new CassiniRestBridge(router, invoker);
        var res = TestServerLauncher.start(bridge);
        this.server = res.server;
        this.port = res.port;
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).GET().build());
    }

    private HttpResponse<String> post(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody()).build());
    }

    private HttpResponse<String> send(HttpRequest req) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                .send(req, HttpResponse.BodyHandlers.ofString());
    }
}
