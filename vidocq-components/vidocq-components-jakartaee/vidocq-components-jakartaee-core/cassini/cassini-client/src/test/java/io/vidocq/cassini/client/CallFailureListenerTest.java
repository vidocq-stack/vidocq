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
package io.vidocq.cassini.client;

import io.vidocq.cassini.client.support.RecordingServer;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A call that ends before its response filters all ran hands its failure to the {@code Consumer<Throwable>} a request
 * filter left under {@value #PROPERTY}: Jakarta REST gives a client filter no callback for a failed call, so the
 * telemetry filters could not end the CLIENT span they opened (Vidocq/vidocq#686). Cyrano reads the same property.
 */
class CallFailureListenerTest {

    private static final String PROPERTY = "io.vidocq.rest.client.failure-listener";

    private final List<Throwable> failures = new CopyOnWriteArrayList<>();
    private final ClientRequestFilter listening =
            ctx -> ctx.setProperty(PROPERTY, (Consumer<Throwable>) failures::add);

    @Test
    void a_refused_connection_tells_the_listener() throws Exception {
        String deadUrl;
        try (RecordingServer dead = RecordingServer.echo()) {
            deadUrl = dead.baseUrl();
        }
        try (Client client = ClientBuilder.newClient()) {
            client.register(listening);
            Invocation.Builder builder = client.target(deadUrl).request();
            ProcessingException e = assertThrows(ProcessingException.class, builder::get);
            assertEquals(List.of(e), failures, "told once, with what the caller receives");
            assertInstanceOf(IOException.class, e.getCause());
        }
    }

    @Test
    void an_asynchronous_call_that_fails_tells_the_listener() throws Exception {
        String deadUrl;
        try (RecordingServer dead = RecordingServer.echo()) {
            deadUrl = dead.baseUrl();
        }
        try (Client client = ClientBuilder.newClient()) {
            client.register(listening);
            var future = client.target(deadUrl).request().async().get();
            ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
            assertEquals(1, failures.size());
            assertSame(e.getCause(), failures.getFirst());
        }
    }

    @Test
    void a_response_filter_that_throws_tells_the_listener() throws Exception {
        IllegalStateException boom = new IllegalStateException("boom");
        try (RecordingServer server = RecordingServer.echo(); Client client = ClientBuilder.newClient()) {
            client.register(listening);
            client.register((ClientResponseFilter) (_, _) -> { throw boom; });
            Invocation.Builder builder = client.target(server.baseUrl()).request();
            assertSame(boom, assertThrows(IllegalStateException.class, builder::get));
            assertEquals(List.of(boom), failures);
        }
    }

    @Test
    void a_call_whose_response_filters_ran_does_not_tell_the_listener() throws Exception {
        try (RecordingServer server = RecordingServer.echo(); Client client = ClientBuilder.newClient()) {
            client.register(listening);
            try (Response response = client.target(server.baseUrl()).request().get()) {
                assertEquals(200, response.getStatus());
            }
            assertTrue(failures.isEmpty(), "no failure: " + failures);
        }
    }

    @Test
    void a_listener_that_throws_does_not_hide_the_failure() throws Exception {
        String deadUrl;
        try (RecordingServer dead = RecordingServer.echo()) {
            deadUrl = dead.baseUrl();
        }
        try (Client client = ClientBuilder.newClient()) {
            client.register((ClientRequestFilter) ctx -> ctx.setProperty(PROPERTY,
                    (Consumer<Throwable>) _ -> { throw new IllegalStateException("listener"); }));
            Invocation.Builder builder = client.target(deadUrl).request();
            ProcessingException e = assertThrows(ProcessingException.class, builder::get);
            assertInstanceOf(IOException.class, e.getCause());
        }
    }
}
