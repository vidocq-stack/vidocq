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
package io.vidocq.runtime.devservices.postgres;

import io.vidocq.runtime.devservices.spi.DevServiceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.DockerClientFactory;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PostgresDevServiceTest {

    @Test
    void appliesWhenNoPoolUrlIsConfigured() {
        assertTrue(new PostgresDevService().appliesWhen(ctx(Map.of())));
    }

    @Test
    void optsOutWhenPoolUrlIsAlreadyConfigured() {
        DevServiceContext ctx = ctx(Map.of("vidocq.pool.url", "jdbc:postgresql://db.example:5432/app"));
        assertFalse(new PostgresDevService().appliesWhen(ctx));
    }

    @Test
    @Timeout(180)
    void startsContainerAndPublishesAReachablePool() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker not available — skipping");

        PostgresDevService svc = new PostgresDevService();
        try {
            Map<String, String> props = svc.start(ctx(Map.of("vidocq.dev.postgres.db", "arago")));

            String url = props.get("vidocq.pool.url");
            assertTrue(url != null && url.startsWith("jdbc:postgresql://"), "got " + url);
            assertEquals("vidocq", props.get("vidocq.pool.username"));
            assertEquals("vidocq", props.get("vidocq.pool.password"));

            // Prove the endpoint is live without dragging in a JDBC driver: open a TCP socket
            // to the host:port encoded in the JDBC URL.
            URI uri = URI.create(url.substring("jdbc:".length())); // postgresql://host:port/db?...
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 5000);
                assertTrue(s.isConnected());
            }
        } finally {
            svc.stop();
        }
    }

    private static DevServiceContext ctx(Map<String, String> props) {
        return new DevServiceContext() {
            @Override public Optional<String> property(String key) {
                String v = props.get(key);
                return (v == null || v.isBlank()) ? Optional.empty() : Optional.of(v);
            }
            @Override public Map<String, String> properties() { return props; }
            @Override public Path basedir() { return Path.of("."); }
            @Override public Path resolve(String relative) { return Path.of(".").resolve(relative); }
            @Override public System.Logger log() { return System.getLogger("test"); }
        };
    }
}
