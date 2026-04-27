package io.vidocq.mpserver.it.rest;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests d'integration Arquillian pour l'extension REST Vidocq.
 * <p>
 * Demarre un serveur Vidocq embarque avec les resources de test,
 * puis effectue des requetes HTTP pour verifier le fonctionnement.
 * </p>
 */
@ExtendWith(ArquillianExtension.class)
@RunAsClient
class RestExtensionIT {

    @ArquillianResource
    private URL baseUrl;

    @Deployment
    public static JavaArchive createDeployment() {
        return ShrinkWrap.create(JavaArchive.class, "vidocq-rest-it.jar")
                .addClass(TestResource.class)
                .addClass(InjectedTestResource.class)
                .addClass(TestService.class);
    }

    @Test
    void shouldReturnPlainText() throws IOException {
        var response = httpGet("/test");
        assertEquals(200, response.statusCode);
        assertEquals("hello-vidocq", response.body);
    }

    @Test
    void shouldReturnJson() throws IOException {
        var response = httpGet("/test/json");
        assertEquals(200, response.statusCode);
        assertTrue(response.body.contains("\"status\":\"ok\""));
    }

    @Test
    void shouldInjectCdiBeans() throws IOException {
        var response = httpGet("/injected");
        assertEquals(200, response.statusCode);
        assertEquals("hello-from-cdi", response.body);
    }

    @Test
    void shouldReturn404ForUnknownPath() throws IOException {
        var response = httpGet("/unknown");
        assertEquals(404, response.statusCode);
    }

    private SimpleResponse httpGet(String path) throws IOException {
        URL url = new URL(baseUrl, path.substring(1));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        int statusCode = conn.getResponseCode();
        String body = "";
        InputStream is = statusCode < 400 ? conn.getInputStream() : conn.getErrorStream();
        if (is != null) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            is.close();
        }
        conn.disconnect();
        return new SimpleResponse(statusCode, body);
    }

    private record SimpleResponse(int statusCode, String body) {}
}
