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
package io.vidocq.chappe.grpc.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;

/** What a request carries, and the metadata it refuses before anything reaches the wire. */
class GrpcRequestTest {

    @Test
    void the_path_is_the_service_then_the_method() {
        var request = GrpcRequest.builder("opentelemetry.proto.collector.trace.v1.TraceService", "Export")
                .build();

        assertEquals("/opentelemetry.proto.collector.trace.v1.TraceService/Export", request.path());
        assertEquals(0, request.message().length);
        assertTrue(request.deadline().isEmpty());
    }

    @Test
    void the_message_is_copied_in_and_out() {
        byte[] payload = "abc".getBytes(StandardCharsets.UTF_8);
        var request = GrpcRequest.builder("s.S", "M").message(payload).build();
        payload[0] = 'x';
        request.message()[1] = 'x';

        assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), request.message());
    }

    @Test
    void metadata_names_are_lower_cased_and_binary_values_base64_encoded_without_padding() {
        var request = GrpcRequest.builder("s.S", "M")
                .metadata("Authorization", "Bearer t")
                .metadata("x-trace-bin", new byte[] {1, 2, 3, 4})
                .metadata("authorization", "second")
                .deadline(Duration.ofSeconds(3))
                .build();

        assertEquals(2, request.metadata().all("authorization").size());
        assertEquals("AQIDBA", request.metadata().firstOrNull("x-trace-bin"));
        assertEquals(Duration.ofSeconds(3), request.deadline().orElseThrow());
    }

    @Test
    void reserved_and_malformed_metadata_is_refused() {
        var builder = GrpcRequest.builder("s.S", "M");
        for (String name : new String[] {
            "grpc-timeout", "grpc-status", ":path", "content-type", "te", "connection", "host", "bad name", "", "A/B"
        }) {
            assertThrows(IllegalArgumentException.class, () -> builder.metadata(name, "v"), name);
        }
        assertThrows(IllegalArgumentException.class, () -> builder.metadata("x-a", "line\nbreak"));
        assertThrows(IllegalArgumentException.class, () -> builder.metadata("x-a", "café"));
        assertThrows(IllegalArgumentException.class, () -> builder.metadata("x-a-bin", "text"));
        assertThrows(IllegalArgumentException.class, () -> builder.metadata("x-a", new byte[] {1}));
    }

    @Test
    void the_service_the_method_and_the_deadline_are_checked() {
        assertThrows(IllegalArgumentException.class, () -> GrpcRequest.builder("", "M"));
        assertThrows(IllegalArgumentException.class, () -> GrpcRequest.builder("s/S", "M"));
        assertThrows(IllegalArgumentException.class, () -> GrpcRequest.builder("s.S", "M/N"));
        assertThrows(IllegalArgumentException.class, () -> GrpcRequest.builder("s.S", "M x"));
        var builder = GrpcRequest.builder("s.S", "M");
        Duration zero = Duration.ZERO;
        Duration negative = Duration.ofSeconds(-1);
        assertThrows(IllegalArgumentException.class, () -> builder.deadline(zero));
        assertThrows(IllegalArgumentException.class, () -> builder.deadline(negative));
        assertFalse(builder.build().deadline().isPresent());
    }
}
