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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import io.vidocq.chappe.api.GrpcStatus;
import io.vidocq.chappe.api.Headers;

import org.junit.jupiter.api.Test;

/**
 * How a finished HTTP/2 response becomes a gRPC outcome: the gRPC status from the trailers, or from the HTTP status
 * when the call never reached a gRPC server (gRPC's {@code http-grpc-status-mapping.md}), and the one message a
 * unary call returns.
 */
class GrpcResponsesTest {

    private static final int MAX = 1024;
    private static final Headers GRPC_HEADERS = Headers.of("content-type", "application/grpc");

    private static byte[] framed(int flag, byte[] payload) {
        var out = new ByteArrayOutputStream();
        out.write(flag);
        out.write(payload.length >>> 24);
        out.write(payload.length >>> 16);
        out.write(payload.length >>> 8);
        out.write(payload.length);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static Headers status(String code) {
        return Headers.of("grpc-status", code);
    }

    @Test
    void an_ok_call_returns_its_one_message() {
        byte[] body = framed(0, "pong".getBytes(StandardCharsets.UTF_8));

        GrpcResponse response = GrpcResponses.assemble(200, GRPC_HEADERS, body, status("0"), MAX);

        assertTrue(response.isOk());
        assertArrayEquals(
                "pong".getBytes(StandardCharsets.UTF_8), response.message().orElseThrow());
        assertEquals("application/grpc", response.headers().firstOrNull("content-type"));
        assertEquals("0", response.trailers().firstOrNull("grpc-status"));
    }

    @Test
    void the_content_type_may_carry_a_sub_type() {
        var headers = Headers.of("content-type", "application/grpc+proto");

        assertTrue(GrpcResponses.assemble(200, headers, framed(0, new byte[0]), status("0"), MAX)
                .isOk());
    }

    @Test
    void a_trailers_only_response_carries_its_status_in_the_headers() {
        var headers = Headers.builder()
                .add("content-type", "application/grpc")
                .add("grpc-status", "5")
                .add("grpc-message", "no%20such%20thing")
                .build();

        GrpcResponse response = GrpcResponses.assemble(200, headers, new byte[0], null, MAX);

        assertEquals(GrpcStatus.NOT_FOUND, response.status());
        assertEquals("no such thing", response.statusMessage());
        assertTrue(response.message().isEmpty());
    }

    @Test
    void the_status_message_is_percent_decoded_as_utf_8_and_a_broken_escape_kept_as_is() {
        var trailers = Headers.builder()
                .add("grpc-status", "3")
                .add("grpc-message", "caf%C3%A9 100%25 %zz %4")
                .build();

        GrpcResponse response = GrpcResponses.assemble(200, GRPC_HEADERS, new byte[0], trailers, MAX);

        assertEquals(GrpcStatus.INVALID_ARGUMENT, response.status());
        assertEquals("café 100% %zz %4", response.statusMessage());
    }

    @Test
    void a_non_ok_status_ignores_the_body() {
        GrpcResponse response = GrpcResponses.assemble(200, GRPC_HEADERS, new byte[] {9, 9}, status("14"), MAX);

        assertEquals(GrpcStatus.UNAVAILABLE, response.status());
        assertTrue(response.message().isEmpty());
    }

    @Test
    void an_http_status_other_than_200_maps_to_a_grpc_status() {
        assertEquals(GrpcStatus.INTERNAL, httpOutcome(400));
        assertEquals(GrpcStatus.UNAUTHENTICATED, httpOutcome(401));
        assertEquals(GrpcStatus.PERMISSION_DENIED, httpOutcome(403));
        assertEquals(GrpcStatus.UNIMPLEMENTED, httpOutcome(404));
        assertEquals(GrpcStatus.UNAVAILABLE, httpOutcome(429));
        assertEquals(GrpcStatus.UNAVAILABLE, httpOutcome(502));
        assertEquals(GrpcStatus.UNAVAILABLE, httpOutcome(503));
        assertEquals(GrpcStatus.UNAVAILABLE, httpOutcome(504));
        assertEquals(GrpcStatus.UNKNOWN, httpOutcome(500));
        assertEquals(GrpcStatus.UNKNOWN, httpOutcome(418));
    }

    private static int httpOutcome(int httpStatus) {
        GrpcResponse response = GrpcResponses.assemble(httpStatus, Headers.empty(), new byte[0], null, MAX);
        assertTrue(response.statusMessage().contains("HTTP status " + httpStatus), response.statusMessage());
        return response.status();
    }

    @Test
    void a_response_that_is_not_grpc_is_unknown() {
        var headers = Headers.of("content-type", "text/html");

        GrpcResponse response = GrpcResponses.assemble(200, headers, new byte[0], status("0"), MAX);

        assertEquals(GrpcStatus.UNKNOWN, response.status());
        assertTrue(response.statusMessage().contains("text/html"), response.statusMessage());
    }

    @Test
    void a_missing_or_unreadable_status_is_unknown() {
        assertEquals(
                GrpcStatus.UNKNOWN,
                GrpcResponses.assemble(200, GRPC_HEADERS, framed(0, new byte[0]), Headers.empty(), MAX)
                        .status());
        assertEquals(
                GrpcStatus.UNKNOWN,
                GrpcResponses.assemble(200, GRPC_HEADERS, new byte[0], status("abc"), MAX)
                        .status());
        GrpcResponse outOfRange = GrpcResponses.assemble(200, GRPC_HEADERS, new byte[0], status("17"), MAX);
        assertEquals(GrpcStatus.UNKNOWN, outOfRange.status());
        assertTrue(outOfRange.statusMessage().contains("17"), outOfRange.statusMessage());
    }

    @Test
    void a_compressed_message_is_refused_by_name() {
        var headers = Headers.builder()
                .add("content-type", "application/grpc")
                .add("grpc-encoding", "gzip")
                .build();

        GrpcResponse response = GrpcResponses.assemble(200, headers, framed(1, new byte[] {1}), status("0"), MAX);

        assertEquals(GrpcStatus.INTERNAL, response.status());
        assertTrue(response.statusMessage().contains("compressed"), response.statusMessage());
        assertTrue(response.statusMessage().contains("gzip"), response.statusMessage());
    }

    @Test
    void an_ok_unary_call_needs_exactly_one_whole_message() {
        assertEquals(
                GrpcStatus.INTERNAL,
                GrpcResponses.assemble(200, GRPC_HEADERS, new byte[0], status("0"), MAX)
                        .status());
        byte[] two = new byte[10];
        two[5] = 0;
        assertEquals(
                GrpcStatus.INTERNAL,
                GrpcResponses.assemble(200, GRPC_HEADERS, two, status("0"), MAX).status());
        assertEquals(
                GrpcStatus.INTERNAL,
                GrpcResponses.assemble(200, GRPC_HEADERS, new byte[] {0, 0, 0, 0, 9, 1}, status("0"), MAX)
                        .status());
        assertEquals(
                GrpcStatus.INTERNAL,
                GrpcResponses.assemble(200, GRPC_HEADERS, new byte[] {0, 0}, status("0"), MAX)
                        .status());
    }

    @Test
    void a_message_past_the_limit_is_resource_exhausted() {
        GrpcResponse response =
                GrpcResponses.assemble(200, GRPC_HEADERS, framed(0, new byte[MAX + 1]), status("0"), MAX);

        assertEquals(GrpcStatus.RESOURCE_EXHAUSTED, response.status());
    }

    @Test
    void every_code_has_its_name() {
        assertEquals("OK", GrpcResponses.codeName(0));
        assertEquals("DEADLINE_EXCEEDED", GrpcResponses.codeName(4));
        assertEquals("UNAUTHENTICATED", GrpcResponses.codeName(16));
        assertEquals("CODE_42", GrpcResponses.codeName(42));
        assertTrue(GrpcResponses.failure(GrpcStatus.UNAVAILABLE, "down", null)
                .toString()
                .contains("UNAVAILABLE"));
    }
}
