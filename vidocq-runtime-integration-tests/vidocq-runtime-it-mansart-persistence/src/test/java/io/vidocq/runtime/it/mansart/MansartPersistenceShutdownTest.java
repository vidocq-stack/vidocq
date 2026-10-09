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

import io.vidocq.runtime.arquillian.VidocqContainerConfig;
import io.vidocq.runtime.arquillian.VidocqEmbeddedContainer;
import jakarta.persistence.EntityManagerFactory;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MansartPersistenceShutdownTest {
    @Test
    void deploymentShutdownClosesFactoryButLeavesApplicationDataSourceOpen() throws Exception {
        JavaArchive archive = PersistenceTestDeployment.create("vidocq-mansart-shutdown-it.jar");
        VidocqEmbeddedContainer container = new VidocqEmbeddedContainer();
        container.setup(new VidocqContainerConfig());
        container.start();
        ProtocolMetaData metadata = container.deploy(archive);
        EntityManagerFactory factory;
        try {
            HTTPContext context = metadata.getContexts(HTTPContext.class).iterator().next();
            HttpURLConnection connection = (HttpURLConnection) URI.create(
                    "http://" + context.getHost() + ":" + context.getPort() + "/persistence/factory")
                    .toURL().openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            assertEquals(200, connection.getResponseCode());
            try (var stream = connection.getInputStream()) {
                assertEquals("managed", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            } finally {
                connection.disconnect();
            }
            factory = PersistenceResource.lastInjectedFactory;
            assertNotNull(factory);
        } finally {
            container.undeploy(archive);
            container.stop();
        }

        assertFalse(factory.isOpen());
        assertFalse(TestDataSource.closed());
    }
}
