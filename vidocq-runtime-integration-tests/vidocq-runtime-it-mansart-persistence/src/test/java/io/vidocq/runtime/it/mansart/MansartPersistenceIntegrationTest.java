/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.it.mansart;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(ArquillianExtension.class)
@RunAsClient
class MansartPersistenceIntegrationTest {
    @ArquillianResource
    URL baseUrl;

    @Deployment
    public static JavaArchive createDeployment() {
        return PersistenceTestDeployment.create("vidocq-mansart-persistence-it.jar");
    }

    @Test
    void persistenceContextCommitsWithTheContainerTransaction() throws IOException {
        assertEquals("1", request("/persistence/commit/committed"));
    }

    @Test
    void persistenceContextRollsBackWithTheContainerTransaction() throws IOException {
        assertEquals("0", request("/persistence/rollback/rolled-back"));
    }

    @Test
    void injectedFactoryIsOwnedByTheContainer() throws IOException {
        assertEquals("managed", request("/persistence/factory"));
    }

    @Test
    void persistenceContextMaintainsEntityIdentityWithinATransaction() throws IOException {
        assertEquals("same", request("/persistence/identity/identity"));
    }

    @Test
    void unsynchronizedPersistenceContextRequiresAnExplicitJoin() throws IOException {
        assertEquals("0", request("/persistence/unsynchronized/unjoined"));
        assertEquals("1", request("/persistence/unsynchronized-join/joined"));
    }

    private String request(String path) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl, path.substring(1)).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        int status = connection.getResponseCode();
        try (var stream = status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
            String body = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(200, status, body);
            return body;
        } finally {
            connection.disconnect();
        }
    }
}
