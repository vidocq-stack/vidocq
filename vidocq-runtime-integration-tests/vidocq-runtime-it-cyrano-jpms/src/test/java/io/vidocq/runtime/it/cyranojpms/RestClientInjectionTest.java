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
package io.vidocq.runtime.it.cyranojpms;

import com.sun.net.httpserver.HttpServer;
import io.vidocq.runtime.core.VidocqBootstrap;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BUG-20261008-01: {@code @Inject @RestClient} in an application compiled with the Rest Client codegen bundle on its
 * processor path. Cyrano's extension runs in the Vauban processor, which records the {@code @RestClient} bean of
 * {@link GreetingClient}; the runtime creates it at boot. That the main sources compiled with validation on is the
 * first half of the assertion.
 */
class RestClientInjectionTest {

    @Test
    void injectsTheRestClientAndCallsThroughIt() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", GreetingClient.PORT), 0);
        server.createContext("/greeting", exchange -> {
            byte[] body = "Hello from the server".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        VidocqBootstrap bootstrap = VidocqBootstrap.create().configure().start();
        try {
            assertEquals("relayed: Hello from the server", CDI.current().select(Relay.class).get().relay());
        } finally {
            bootstrap.shutdown();
            server.stop(0);
        }
    }
}
