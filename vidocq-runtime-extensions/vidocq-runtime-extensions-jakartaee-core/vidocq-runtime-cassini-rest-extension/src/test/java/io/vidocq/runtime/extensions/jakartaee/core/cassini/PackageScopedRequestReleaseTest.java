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
package io.vidocq.runtime.extensions.jakartaee.core.cassini;

import io.vidocq.cassini.cdi.vauban.VaubanBeanProvider;
import io.vidocq.cassini.chappe.ChappeHttpAdapter;
import io.vidocq.cassini.spi.http.CassiniStack;
import io.vidocq.chappe.api.Server;
import io.vidocq.runtime.extensions.jakartaee.core.cassini.CassiniMountHandlerProvider.PackageScopedBeanProvider;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.Dependent;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Application;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A mount that filters its packages serves a {@code @Dependent} resource as the unfiltered one does: Cassini ends it
 * when its request ends, through {@code BeanProvider.getRequestBean} (CASSINI-062). The package filter delegated
 * {@code getBean} only, so the release never reached Cassini, and the {@code @PreDestroy} never ran (BUG-20261118-01).
 */
class PackageScopedRequestReleaseTest {

    static final AtomicInteger CREATED = new AtomicInteger();
    static final AtomicInteger DESTROYED = new AtomicInteger();

    @Path("/released")
    @Dependent
    public static class ReleasedResource {

        @PostConstruct
        void created() {
            CREATED.incrementAndGet();
        }

        @PreDestroy
        void destroyed() {
            DESTROYED.incrementAndGet();
        }

        @GET
        public String get() {
            return "released";
        }
    }

    private VaubanContainer container;
    private Server server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop();
        }
        if (container != null) {
            container.close();
        }
    }

    @Test
    void aFilteredMountEndsADependentResourceWithEachRequest() throws Exception {
        CREATED.set(0);
        DESTROYED.set(0);
        // The lookup of this module: no processor generated a component provider for the test classes
        container = VaubanContainer.builder().lookup(MethodHandles.lookup()).addBeanClass(ReleasedResource.class)
                .build();
        var scoped = new PackageScopedBeanProvider(new VaubanBeanProvider(container),
                List.of(ReleasedResource.class.getPackageName()), List.of());
        var stack = CassiniStack.builder().beanProvider(scoped).application(new Application() {}).build();
        // Port 0: the server keeps the free port the system gives it
        server = Server.builder().host("127.0.0.1").port(0).handler(new ChappeHttpAdapter(stack.adapter())).build();
        server.start();
        // HttpURLConnection: this module does not read java.net.http
        var url = URI.create("http://127.0.0.1:" + server.port() + "/released").toURL();
        for (int i = 0; i < 2; i++) {
            var connection = (HttpURLConnection) url.openConnection();
            try (var body = connection.getInputStream()) {
                assertEquals(200, connection.getResponseCode());
                assertEquals("released", new String(body.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            } finally {
                connection.disconnect();
            }
        }
        assertAll(() -> assertEquals(2, CREATED.get(), "one resource per request"),
                () -> assertEquals(2, DESTROYED.get(), "each ended with its request"));
    }
}
