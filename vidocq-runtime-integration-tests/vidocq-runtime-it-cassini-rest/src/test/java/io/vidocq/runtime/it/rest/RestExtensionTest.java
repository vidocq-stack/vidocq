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
package io.vidocq.runtime.it.rest;

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
 * Arquillian integration tests for the Vidocq REST extension.
 * <p>
 * Starts an embedded Vidocq server with the test resources,
 * then makes HTTP requests to verify operation.
 * </p>
 */
@ExtendWith(ArquillianExtension.class)
@RunAsClient
class RestExtensionTest {

    @ArquillianResource
    private URL baseUrl;

    @Deployment
    public static JavaArchive createDeployment() {
        return ShrinkWrap.create(JavaArchive.class, "vidocq-rest-it.jar")
                .addClass(TestResource.class)
                .addClass(InjectedTestResource.class)
                .addClass(TestService.class)
                .addClass(ComposedService.class)
                .addClass(ComposedResource.class);
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
    void shouldProxyABeanWithOnlyAnInjectConstructor() throws IOException {
        // Vidocq/vauban#24: ComposedService declares no no-arg constructor and its @Inject
        // constructor dereferences its parameter. The woven (ProxyLink) entry constructor
        // makes the client proxy viable without any workaround in the bean.
        var response = httpGet("/composed");
        assertEquals(200, response.statusCode);
        assertEquals("hello-from-cdi-composed", response.body);
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
