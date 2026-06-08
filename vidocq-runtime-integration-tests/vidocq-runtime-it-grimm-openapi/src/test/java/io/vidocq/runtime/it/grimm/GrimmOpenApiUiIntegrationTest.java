/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.it.grimm;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Arquillian end-to-end tests proving the full chain works inside a running vidocq deployment:
 * <ul>
 *   <li>Grimm serves the OpenAPI document at {@code /openapi} (extension A);</li>
 *   <li>the OpenAPI UI extension serves Swagger UI at {@code /openapi/ui} (extension B), same
 *       origin as the document.</li>
 * </ul>
 */
@ExtendWith(ArquillianExtension.class)
@RunAsClient
class GrimmOpenApiUiIntegrationTest {

    @ArquillianResource
    private URL baseUrl;

    @Deployment
    public static JavaArchive createDeployment() {
        return ShrinkWrap.create(JavaArchive.class, "vidocq-grimm-openapi-it.jar")
                .addClass(GreetingResource.class);
    }

    @Test
    void openApiDocumentIsServedAsJson() throws IOException {
        SimpleResponse r = httpGet("/openapi?format=json");
        assertEquals(200, r.statusCode());
        assertTrue(r.contentType() != null && r.contentType().contains("application/json"),
                "expected JSON content-type, got: " + r.contentType());
        assertTrue(r.body().contains("\"openapi\""), "OpenAPI doc must declare the openapi version");
        assertTrue(r.body().contains("/greet"), "OpenAPI doc must list the scanned /greet path");
    }

    @Test
    void swaggerUiIndexIsServed() throws IOException {
        SimpleResponse r = httpGet("/openapi/ui/");
        assertEquals(200, r.statusCode());
        assertTrue(r.contentType() != null && r.contentType().contains("text/html"),
                "expected HTML content-type, got: " + r.contentType());
        assertTrue(r.body().contains("swagger-ui"), "index.html must reference the swagger-ui assets");
    }

    @Test
    void swaggerInitializerPointsAtOpenApi() throws IOException {
        SimpleResponse r = httpGet("/openapi/ui/swagger-initializer.js");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("/openapi?format=json"),
                "initializer must point Swagger UI at the Grimm OpenAPI document");
    }

    @Test
    void swaggerUiBundleAssetIsServed() throws IOException {
        SimpleResponse r = httpGet("/openapi/ui/swagger-ui-bundle.js");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().length() > 1000, "the vendored bundle must be served from the classpath");
    }

    private SimpleResponse httpGet(String path) throws IOException {
        URL url = new URL(baseUrl, path.substring(1));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        int statusCode = conn.getResponseCode();
        String contentType = conn.getContentType();
        String body = "";
        InputStream is = statusCode < 400 ? conn.getInputStream() : conn.getErrorStream();
        if (is != null) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            is.close();
        }
        conn.disconnect();
        return new SimpleResponse(statusCode, contentType, body);
    }

    private record SimpleResponse(int statusCode, String contentType, String body) {}
}
