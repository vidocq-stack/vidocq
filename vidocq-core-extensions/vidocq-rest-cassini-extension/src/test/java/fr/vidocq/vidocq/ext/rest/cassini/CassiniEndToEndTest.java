package fr.vidocq.vidocq.ext.rest.cassini;

import fr.vidocq.chappe.api.Server;
import fr.vidocq.vidocq.ext.rest.cassini.internal.CassiniRestBridge;
import fr.vidocq.vidocq.ext.rest.cassini.internal.Invoker;
import fr.vidocq.vidocq.ext.rest.cassini.internal.ResourceMethod;
import fr.vidocq.vidocq.ext.rest.cassini.internal.ResourceScanner;
import fr.vidocq.vidocq.ext.rest.cassini.internal.UriRouter;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
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

    @Path("/users")
    public static class UserResource {
        @GET @Path("/me")     public String me()      { return "current-user"; }
        @GET @Path("/{id}")   public String byId()    { return "by-id"; }
    }

    @Path("/body")
    public static class BodyResource {
        @POST @Path("/echo-string")
        @Consumes("text/plain") @Produces("text/plain")
        public String echoString(String body) { return "echo:" + body; }

        @POST @Path("/echo-bytes")
        @Consumes("application/octet-stream")
        @Produces("application/octet-stream")
        public byte[] echoBytes(byte[] body) { return body; }

        @PUT @Path("/json-only")
        @Consumes("application/json")
        public String jsonOnly(String body) { return "got:" + body; }

        @GET @Path("/json")
        @Produces("application/json")
        public String json() { return "{\"k\":1}"; }

        @GET @Path("/xml")
        @Produces("application/xml")
        public String xml() { return "<k>1</k>"; }
    }

    @Path("/params")
    public static class ParamResource {
        @GET @Path("/path/{id}")
        public String path(@PathParam("id") int id) {
            return "id=" + id;
        }

        @GET @Path("/query")
        public String query(@QueryParam("q") String q,
                            @QueryParam("n") @DefaultValue("5") int n) {
            return "q=" + q + ";n=" + n;
        }

        @GET @Path("/tags")
        public String tags(@QueryParam("tag") List<String> tags) {
            return "tags=" + String.join(",", tags);
        }

        @GET @Path("/header")
        public String header(@HeaderParam("X-User") String user) {
            return "user=" + user;
        }

        @GET @Path("/cookie")
        public String cookie(@CookieParam("session") String sid) {
            return "sid=" + sid;
        }

        @POST @Path("/form")
        public String form(@FormParam("name") String name,
                           @FormParam("age") int age) {
            return "name=" + name + ";age=" + age;
        }
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

    @Test
    void literalBeatsTemplate() throws Exception {
        start(new UserResource());

        assertEquals("current-user", get("/users/me").body());
        assertEquals("by-id", get("/users/42").body());
    }

    @Test
    void pathParamCoercesToInt() throws Exception {
        start(new ParamResource());
        assertEquals("id=42", get("/params/path/42").body());
    }

    @Test
    void queryParamWithDefault() throws Exception {
        start(new ParamResource());
        assertEquals("q=hi;n=5", get("/params/query?q=hi").body());
        assertEquals("q=hi;n=12", get("/params/query?q=hi&n=12").body());
    }

    @Test
    void queryParamAsList() throws Exception {
        start(new ParamResource());
        assertEquals("tags=a,b,c", get("/params/tags?tag=a&tag=b&tag=c").body());
    }

    @Test
    void headerParam() throws Exception {
        start(new ParamResource());
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/params/header"))
                .header("X-User", "claude").GET().build());
        assertEquals("user=claude", r.body());
    }

    @Test
    void cookieParam() throws Exception {
        start(new ParamResource());
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/params/cookie"))
                .header("Cookie", "session=xyz; tracker=1").GET().build());
        assertEquals("sid=xyz", r.body());
    }

    @Test
    void formParam() throws Exception {
        start(new ParamResource());
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/params/form"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("name=yann&age=42"))
                .build());
        assertEquals("name=yann;age=42", r.body());
    }

    @Test
    void bodyStringRoundTrip() throws Exception {
        start(new BodyResource());
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/body/echo-string"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("hello"))
                .build());
        assertEquals(200, r.statusCode());
        assertEquals("echo:hello", r.body());
    }

    @Test
    void bodyByteArrayRoundTrip() throws Exception {
        start(new BodyResource());
        byte[] payload = {1, 2, 3, 4, 5};
        HttpResponse<byte[]> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/body/echo-bytes"))
                        .header("Content-Type", "application/octet-stream")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, r.statusCode());
        assertEquals("application/octet-stream",
                r.headers().firstValue("Content-Type").orElse(""));
        org.junit.jupiter.api.Assertions.assertArrayEquals(payload, r.body());
    }

    @Test
    void consumesMismatchYields415() throws Exception {
        start(new BodyResource());
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/body/json-only"))
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString("x"))
                .build());
        assertEquals(415, r.statusCode());
    }

    @Test
    void producesMismatchYields406() throws Exception {
        start(new BodyResource());
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/body/json"))
                .header("Accept", "image/png").GET().build());
        assertEquals(406, r.statusCode());
    }

    @Test
    void acceptNegotiationPicksMatchingProduces() throws Exception {
        start(new BodyResource());
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/body/json"))
                .header("Accept", "application/xml;q=0.1, application/json;q=0.9").GET().build());
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
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
