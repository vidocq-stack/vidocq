package io.vidocq.runtime.ext.chappe;

import io.vidocq.chappe.api.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Starts a real Chappe Server via ChappeEngineExtension + ChappeServerBootstrap
 * and verify that it serves a mounted handler, that the hooks turn, and that it stops properly.
 */
class ChappeServerEndToEndTest {

    private ChappeEngineExtension engine;
    private ChappeServerBootstrap bootstrap;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        this.port = freePort();
        this.engine = new ChappeEngineExtension();
        this.bootstrap = new ChappeServerBootstrap();
        engine.configure(null);
    }

    @AfterEach
    void tearDown() {
        bootstrap.onStop();
        ChappeMountPoint.uninstall();
    }

    @Test
    void handlerMountedOnDefaultListenerIsReachable() throws Exception {
        ChappeMountPoint.instance().router(ChappeListener.DEFAULT)
                .get("/hello", _ -> Response.ok("hi"));

        var ctx = new FakeExtensionContext(TestConfig.of(Map.of(
                "vidocq.chappe.listener.default.port", Integer.toString(port))));
        bootstrap.onStart(ctx);

        HttpResponse<String> resp = get("http://127.0.0.1:" + port + "/hello");
        assertEquals(200, resp.statusCode());
        assertEquals("hi", resp.body());
    }

    @Test
    void beforeAndAfterHooksExecuted() throws Exception {
        AtomicBoolean before = new AtomicBoolean();
        AtomicBoolean after = new AtomicBoolean();
        ChappeMountPoint.instance().addBeforeStartHook(() -> before.set(true));
        ChappeMountPoint.instance().addAfterStopHook(() -> after.set(true));

        var ctx = new FakeExtensionContext(TestConfig.of(Map.of(
                "vidocq.chappe.listener.default.port", Integer.toString(port))));
        bootstrap.onStart(ctx);
        assertTrue(before.get(), "beforeStart hook should have run");

        bootstrap.onStop();
        assertTrue(after.get(), "afterStop hook should have run");
    }

    @Test
    void multiListenerEachServesItsOwnRouter() throws Exception {
        int adminPort = freePort();
        ChappeMountPoint mp = ChappeMountPoint.instance();
        mp.router(ChappeListener.DEFAULT).get("/ping", _ -> Response.ok("public"));
        mp.router("admin").get("/ping", _ -> Response.ok("private"));

        var ctx = new FakeExtensionContext(TestConfig.of(Map.of(
                "vidocq.chappe.listeners", "default,admin",
                "vidocq.chappe.listener.default.port", Integer.toString(port),
                "vidocq.chappe.listener.admin.host", "127.0.0.1",
                "vidocq.chappe.listener.admin.port", Integer.toString(adminPort))));
        bootstrap.onStart(ctx);

        assertEquals("public", get("http://127.0.0.1:" + port + "/ping").body());
        assertEquals("private", get("http://127.0.0.1:" + adminPort + "/ping").body());
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static HttpResponse<String> get(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build();
        return client.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(3))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

}
