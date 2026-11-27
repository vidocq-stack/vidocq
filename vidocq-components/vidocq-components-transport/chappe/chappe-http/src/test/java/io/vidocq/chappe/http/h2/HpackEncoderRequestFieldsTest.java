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
package io.vidocq.chappe.http.h2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import io.vidocq.chappe.api.Headers;

import org.junit.jupiter.api.Test;

/**
 * The request side of the encoder, which Chappe's gRPC client writes with (Vidocq/vidocq#837): the four request
 * pseudo-header fields first (RFC 9113 §8.3.1), then the fields under the same rules as a response.
 */
class HpackEncoderRequestFieldsTest {

    private final HpackEncoder encoder = new HpackEncoder();
    private final HpackDecoder decoder = new HpackDecoder(4096, 8192, 65_536);

    private List<String> decode(byte[] block) throws Exception {
        var fields = new ArrayList<String>();
        decoder.decode(ByteBuffer.wrap(block), (name, value) -> fields.add(name + "=" + value));
        return fields;
    }

    @Test
    void the_pseudo_header_fields_come_first_then_the_fields_in_lower_case() throws Exception {
        Headers headers = Headers.builder()
                .add("Content-Type", "application/grpc")
                .add("te", "trailers")
                .add("X-Tenant", " acme ")
                .build();

        assertEquals(
                List.of(
                        ":method=POST",
                        ":scheme=http",
                        ":authority=collector:4317",
                        ":path=/pkg.Service/Method",
                        "content-type=application/grpc",
                        "te=trailers",
                        "x-tenant=acme"),
                decode(encoder.encodeRequest("POST", "http", "collector:4317", "/pkg.Service/Method", headers)));
    }

    @Test
    void connection_specific_fields_are_not_sent() throws Exception {
        Headers headers = Headers.builder()
                .add("Connection", "close")
                .add("TE", "gzip")
                .add("X-Kept", "yes")
                .build();

        assertEquals(
                List.of(":method=GET", ":scheme=https", ":authority=h", ":path=/", "x-kept=yes"),
                decode(encoder.encodeRequest("GET", "https", "h", "/", headers)));
    }

    @Test
    void a_second_request_reuses_the_dynamic_table() throws Exception {
        Headers headers = Headers.of("x-tenant", "acme");
        byte[] first = encoder.encodeRequest("POST", "http", "collector:4317", "/a.B/C", headers);
        decode(first);
        byte[] second = encoder.encodeRequest("POST", "http", "collector:4317", "/a.B/C", headers);

        assertTrue(second.length < first.length, "the repeated fields are indexed");
        assertEquals(
                List.of(":method=POST", ":scheme=http", ":authority=collector:4317", ":path=/a.B/C", "x-tenant=acme"),
                decode(second));
    }

    @Test
    void an_invalid_field_or_pseudo_header_value_is_refused_before_anything_is_encoded() throws Exception {
        Headers badName = Headers.of("bad name", "v");
        Headers none = Headers.empty();
        assertThrows(IllegalArgumentException.class, () -> encoder.encodeRequest("POST", "http", "h", "/", badName));
        assertThrows(IllegalArgumentException.class, () -> encoder.encodeRequest("POST", "http", "h", "/a\r\nb", none));
        assertThrows(IllegalArgumentException.class, () -> encoder.encodeRequest("PO ST", "http", "h", "/", none));
        assertThrows(IllegalArgumentException.class, () -> encoder.encodeRequest("POST", "http", "h", "", none));

        // The refusals left the dynamic table untouched: a later block still decodes.
        assertEquals(
                List.of(":method=POST", ":scheme=http", ":authority=h", ":path=/"),
                decode(encoder.encodeRequest("POST", "http", "h", "/", Headers.empty())));
    }
}
