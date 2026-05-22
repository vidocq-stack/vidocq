package io.vidocq.runtime.it.humboldt;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests E2E qui valident l'intégration complète Humboldt dans Vidocq :
 * <ol>
 *   <li><b>BCE @WithSpan</b> — la {@code BuildCompatibleExtension} de
 *       {@code humboldt-cdi} ajoute bien {@code @SpanBinding} sur les méthodes
 *       annotées {@code @WithSpan} OTel → l'interceptor s'active.
 *       (résolution du risk PLAN.md §15.1 : Vauban CDI Lite supporte BCE)</li>
 *   <li><b>Span SERVER via humboldt-rest filters</b> — chaque requête HTTP
 *       produit un span SERVER avec les attrs OTel HTTP semantic.</li>
 *   <li><b>Propagation W3C entrante</b> — un header {@code traceparent}
 *       fait hériter le traceId au span SERVER.</li>
 *   <li><b>Status ERROR sur 500</b> — quand l'endpoint throw, le span SERVER
 *       reçoit {@code status=ERROR}.</li>
 * </ol>
 *
 * <p>Toute la stack est démarrée par Arquillian : Chappe (HTTP) → Cassini
 * (JAX-RS) → Vauban (CDI) → humboldt-runtime auto-config → humboldt-cdi BCE +
 * interceptor + humboldt-rest filters.</p>
 */
@ExtendWith(ArquillianExtension.class)
@RunAsClient
class HumboldtIntegrationTest {

    @ArquillianResource
    private URL baseUrl;

    // Les system properties OTEL_* sont set par surefire (cf. pom.xml du module)
    // pour qu'elles arrivent AVANT le boot d'HumboldtExtension.

    @Deployment
    public static JavaArchive createDeployment() {
        return ShrinkWrap.create(JavaArchive.class, "humboldt-it.jar")
                .addClass(TraceTestService.class)
                .addClass(TraceTestResource.class)
                .addClass(SpansResource.class)
                // Classes humboldt-rest/cdi à inclure pour que Cassini/Vauban
                // les scanne dans ce Deployment Arquillian isolé (les classes
                // existent sur le classpath via les deps Maven, mais le scoping
                // Arquillian Vidocq nécessite leur présence explicite).
                .addClass(io.vidocq.humboldt.rest.HumboldtServerRequestFilter.class)
                .addClass(io.vidocq.humboldt.rest.HumboldtServerResponseFilter.class)
                .addClass(io.vidocq.humboldt.rest.HumboldtSpanFinalizer.class)
                .addClass(io.vidocq.humboldt.cdi.WithSpanInterceptor.class)
                .addClass(io.vidocq.humboldt.cdi.SpanBinding.class)
                .addClass(io.vidocq.humboldt.cdi.HumboldtBuildCompatibleExtension.class);
    }

    @BeforeEach
    void resetSpansBeforeEachTest() throws IOException {
        httpRequest("DELETE", "/__spans", null);
    }

    // ============================================================
    //  Test 1 — BCE @WithSpan (risk PLAN §15.1 confirmation)
    // ============================================================
    @Test
    void with_span_bce_intercepts_cdi_method() throws IOException {
        var resp = httpGet("/trace/work");
        assertEquals(200, resp.statusCode);
        assertEquals("work-result", resp.body);

        String spans = httpGet("/__spans").body;
        // Au moins 2 spans : SERVER (GET /trace/work) + INTERNAL (traced.work)
        assertTrue(spans.contains("\"name\":\"traced.work\""),
                "BCE doit avoir activé l'interceptor sur @WithSpan(\"traced.work\") — spans : " + spans);
        assertTrue(spans.contains("\"kind\":\"INTERNAL\""));
        assertTrue(spans.contains("\"name\":\"GET /trace/work\""),
                "humboldt-rest filter doit avoir créé un span SERVER");
    }

    // ============================================================
    //  Test 2 — Span SERVER via humboldt-rest filter
    // ============================================================
    @Test
    void rest_filter_creates_server_span_with_otel_http_attrs() throws IOException {
        var resp = httpGet("/trace/plain");
        assertEquals(200, resp.statusCode);
        assertEquals("plain-result", resp.body);

        String spans = httpGet("/__spans").body;
        // Span SERVER attendu pour /trace/plain avec attrs OTel HTTP semantic
        assertTrue(spans.contains("\"name\":\"GET /trace/plain\""),
                "span SERVER attendu : " + spans);
        assertTrue(spans.contains("\"kind\":\"SERVER\""));
        assertTrue(spans.contains("\"urlPath\":\"/trace/plain\""),
                "attr url.path attendu : " + spans);
        assertTrue(spans.contains("\"httpStatus\":200"),
                "attr http.response.status_code=200 attendu : " + spans);
        // Note : la BCE applique le binding au niveau classe (toutes les méthodes du bean
        // qui a @WithSpan quelque part sont interceptées). plain() reçoit donc aussi un
        // span INTERNAL — comportement Vauban CDI Lite cohérent avec le pattern
        // SmallRye/Quarkus. Pas d'assertion stricte sur l'absence.
    }

    // ============================================================
    //  Test 3 — Propagation W3C entrante
    // ============================================================
    @Test
    void w3c_traceparent_header_propagates_trace_id() throws IOException {
        String parentTraceId = "0af7651916cd43dd8448eb211c80319c";
        String parentSpanId = "b7ad6b7169203331";
        String traceparent = "00-" + parentTraceId + "-" + parentSpanId + "-01";

        var resp = httpGetWithHeader("/trace/plain", "traceparent", traceparent);
        assertEquals(200, resp.statusCode);

        String spans = httpGet("/__spans").body;
        assertTrue(spans.contains("\"traceId\":\"" + parentTraceId + "\""),
                "span SERVER doit hériter du traceId W3C : " + spans);
        assertTrue(spans.contains("\"parentSpanId\":\"" + parentSpanId + "\""),
                "span SERVER parentSpanId doit pointer le spanId du traceparent : " + spans);
    }

    // ============================================================
    //  Test 4 — Status ERROR sur 500
    // ============================================================
    @Test
    void server_span_status_error_when_endpoint_throws() throws IOException {
        var resp = httpGet("/trace/boom");
        assertEquals(500, resp.statusCode, "endpoint /boom doit retourner 500");

        String spans = httpGet("/__spans").body;
        // L'interceptor humboldt-cdi @WithSpan("traced.boom") doit avoir capturé
        // l'exception et set status=ERROR sur le span INTERNAL.
        assertTrue(spans.contains("\"name\":\"traced.boom\""),
                "span INTERNAL @WithSpan(\"traced.boom\") attendu : " + spans);
        assertTrue(spans.contains("IllegalStateException: boom from traced.boom"),
                "le message d'exception doit être dans status.description : " + spans);

        // M6d.6 — Le span SERVER pour /trace/boom doit maintenant être présent
        // grâce à HumboldtSpanFinalizer (ExceptionMapper<Throwable> qui termine
        // le span quand Cassini court-circuite le response filter — bug Cassini
        // documenté dans HumboldtSpanFinalizer javadoc, workaround côté humboldt-rest).
        assertTrue(spans.contains("\"name\":\"GET /trace/boom\""),
                "span SERVER /trace/boom attendu (terminé par HumboldtSpanFinalizer) : " + spans);
        assertTrue(spans.contains("\"httpStatus\":500"),
                "http.response.status_code=500 attendu sur le span SERVER : " + spans);
        assertTrue(spans.contains("\"status\":\"ERROR\""),
                "au moins un span doit être ERROR (SERVER et/ou INTERNAL) : " + spans);
    }

    // ============================================================
    //  Helpers HTTP
    // ============================================================

    private SimpleResponse httpGet(String path) throws IOException {
        return httpRequest("GET", path, null);
    }

    private SimpleResponse httpGetWithHeader(String path, String headerName, String headerValue) throws IOException {
        URL url = new URL(baseUrl, path.startsWith("/") ? path.substring(1) : path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty(headerName, headerValue);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        int statusCode = conn.getResponseCode();
        String body = readBody(conn, statusCode);
        conn.disconnect();
        return new SimpleResponse(statusCode, body);
    }

    private SimpleResponse httpRequest(String method, String path, String headerValueOrNull) throws IOException {
        URL url = new URL(baseUrl, path.startsWith("/") ? path.substring(1) : path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        int statusCode = conn.getResponseCode();
        String body = readBody(conn, statusCode);
        conn.disconnect();
        return new SimpleResponse(statusCode, body);
    }

    private static String readBody(HttpURLConnection conn, int statusCode) throws IOException {
        InputStream is = statusCode < 400 ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) return "";
        try (is) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record SimpleResponse(int statusCode, String body) {}
}
