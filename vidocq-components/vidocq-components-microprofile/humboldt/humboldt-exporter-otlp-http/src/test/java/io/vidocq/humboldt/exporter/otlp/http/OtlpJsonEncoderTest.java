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
package io.vidocq.humboldt.exporter.otlp.http;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.KeyValue;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.vidocq.humboldt.exporter.otlp.http.internal.OtlpJsonEncoder;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.trace.data.EventData;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.trace.data.LinkData;
import io.vidocq.humboldt.sdk.trace.data.SpanData;
import io.vidocq.humboldt.sdk.trace.data.StatusData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtlpJsonEncoderTest {

    private static final SpanContext CTX = SpanContext.create(
            "0123456789abcdef0123456789abcdef", "0123456789abcdef",
            TraceFlags.getSampled(), TraceState.getDefault());

    @Test
    void encodes_single_span_with_required_fields() {
        SpanData s = new SpanData(
                CTX, null, "GET /api", SpanKind.SERVER,
                1_000_000_000L, 1_500_000_000L,
                Attributes.of(AttributeKey.stringKey("http.method"), "GET"),
                List.of(), List.of(),
                StatusData.ok(),
                Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "humboldt-test")),
                InstrumentationScope.of("io.vidocq.test"));

        String json = OtlpJsonEncoder.encode(List.of(s));

        assertTrue(json.startsWith("{\"resourceSpans\":["));
        assertTrue(json.contains("\"traceId\":\"0123456789abcdef0123456789abcdef\""));
        assertTrue(json.contains("\"spanId\":\"0123456789abcdef\""));
        assertTrue(json.contains("\"name\":\"GET /api\""));
        assertTrue(json.contains("\"kind\":2"), "SpanKind.SERVER must be encoded as 2");
        assertTrue(json.contains("\"startTimeUnixNano\":\"1000000000\""));
        assertTrue(json.contains("\"endTimeUnixNano\":\"1500000000\""));
        assertTrue(json.contains("\"code\":1"), "StatusCode.OK must be encoded as 1");
        assertTrue(json.contains("\"http.method\""));
        assertTrue(json.contains("\"stringValue\":\"GET\""));
        assertTrue(json.contains("\"service.name\""));
        assertFalse(json.contains("\"parentSpanId\""), "pas de parent → pas de parentSpanId");
    }

    @Test
    void encodes_parent_span_id_when_present() {
        SpanContext parent = SpanContext.create(
                "0123456789abcdef0123456789abcdef", "abcdef9876543210",
                TraceFlags.getSampled(), TraceState.getDefault());
        SpanData s = new SpanData(
                CTX, parent, "child", SpanKind.INTERNAL,
                1L, 2L, Attributes.empty(), List.of(), List.of(),
                StatusData.unset(), Resource.empty(), InstrumentationScope.of("x"));

        String json = OtlpJsonEncoder.encode(List.of(s));
        assertTrue(json.contains("\"parentSpanId\":\"abcdef9876543210\""));
    }

    @Test
    void encodes_events_and_links_and_status() {
        SpanContext linkTarget = SpanContext.create(
                "11111111111111111111111111111111", "2222222222222222",
                TraceFlags.getSampled(), TraceState.getDefault());

        SpanData s = new SpanData(
                CTX, null, "with-extras", SpanKind.CLIENT,
                100L, 200L,
                Attributes.of(AttributeKey.longKey("retries"), 3L),
                List.of(new EventData(150L, "step",
                        Attributes.of(AttributeKey.stringKey("phase"), "init"))),
                List.of(new LinkData(linkTarget,
                        Attributes.of(AttributeKey.stringKey("rel"), "follow"))),
                StatusData.error("boom"),
                Resource.empty(), InstrumentationScope.of("x"));

        String json = OtlpJsonEncoder.encode(List.of(s));

        assertTrue(json.contains("\"events\":[{"));
        assertTrue(json.contains("\"name\":\"step\""));
        assertTrue(json.contains("\"timeUnixNano\":\"150\""));
        assertTrue(json.contains("\"phase\""));

        assertTrue(json.contains("\"links\":[{"));
        assertTrue(json.contains("\"traceId\":\"11111111111111111111111111111111\""));
        assertTrue(json.contains("\"rel\""));

        assertTrue(json.contains("\"code\":2"), "ERROR = 2");
        assertTrue(json.contains("\"message\":\"boom\""));

        assertTrue(json.contains("\"intValue\":\"3\""), "long → intValue string");
    }

    @Test
    void escapes_json_special_characters_in_strings() {
        SpanData s = new SpanData(
                CTX, null, "name with \"quotes\" and \\backslash and \n newline",
                SpanKind.INTERNAL, 1L, 2L,
                Attributes.empty(), List.of(), List.of(),
                StatusData.unset(), Resource.empty(), InstrumentationScope.of("x"));

        String json = OtlpJsonEncoder.encode(List.of(s));
        assertTrue(json.contains("\\\"quotes\\\""), "quotes escaped");
        assertTrue(json.contains("\\\\backslash"), "backslash escaped");
        assertTrue(json.contains("\\n"), "newline escaped");
    }

    @Test
    void empty_collection_produces_empty_resource_spans() {
        String json = OtlpJsonEncoder.encode(List.of());
        assertEquals("{\"resourceSpans\":[]}", json);
    }

    @Test
    void encodes_array_attributes_as_otlp_arrayValue() {
        // Regression: before the OtlpJsonCommon refactor, only the spans encoder handled
        // arrays — metrics/logs had a latent bug. Now all 3 share
        // the same writeAnyValue logic covering STRING_ARRAY/LONG_ARRAY/etc.
        SpanData s = new SpanData(
                CTX, null, "array-test", SpanKind.INTERNAL, 1L, 2L,
                Attributes.builder()
                        .put(AttributeKey.stringArrayKey("tags"), List.of("ci", "ops"))
                        .put(AttributeKey.longArrayKey("retries"), List.of(1L, 2L, 3L))
                        .build(),
                List.of(), List.of(),
                StatusData.unset(),
                Resource.empty(), InstrumentationScope.of("x"));

        String json = OtlpJsonEncoder.encode(List.of(s));

        assertTrue(json.contains("\"arrayValue\":{\"values\":["),
                "array attribute must be encoded as arrayValue: " + json);
        assertTrue(json.contains("\"stringValue\":\"ci\""));
        assertTrue(json.contains("\"stringValue\":\"ops\""));
        assertTrue(json.contains("\"intValue\":\"1\""));
        assertTrue(json.contains("\"intValue\":\"2\""));
        assertTrue(json.contains("\"intValue\":\"3\""));
    }

    @Test
    void encodes_complex_value_attributes_per_the_otlp_json_mapping() {
        // Attributes.builder() narrows homogeneous scalar arrays and scalars to the plain types; what stays
        // AttributeType.VALUE (maps, bytes, mixed arrays, empty) must still become a real AnyValue, never {}.
        SpanData s = new SpanData(
                CTX, null, "value-test", SpanKind.INTERNAL, 1L, 2L,
                Attributes.builder()
                        .put(AttributeKey.valueKey("order"), Value.of(
                                KeyValue.of("id", Value.of("o-1")),
                                KeyValue.of("count", Value.of(2L)),
                                KeyValue.of("paid", Value.of(true)),
                                KeyValue.of("total", Value.of(12.5)),
                                KeyValue.of("lines", Value.of(Value.of("sku-1"), Value.of(3L)))))
                        .put(AttributeKey.valueKey("payload"), Value.of(new byte[] {1, 2, 3}))
                        .put(AttributeKey.valueKey("mixed"), Value.of(Value.of("a"), Value.of(false)))
                        .put(AttributeKey.valueKey("nothing"), Value.empty())
                        .build(),
                List.of(), List.of(),
                StatusData.unset(),
                Resource.empty(), InstrumentationScope.of("x"));

        String json = OtlpJsonEncoder.encode(List.of(s));

        assertTrue(json.contains("{\"key\":\"order\",\"value\":{\"kvlistValue\":{\"values\":["
                + "{\"key\":\"id\",\"value\":{\"stringValue\":\"o-1\"}},"
                + "{\"key\":\"count\",\"value\":{\"intValue\":\"2\"}},"
                + "{\"key\":\"paid\",\"value\":{\"boolValue\":true}},"
                + "{\"key\":\"total\",\"value\":{\"doubleValue\":12.5}},"
                + "{\"key\":\"lines\",\"value\":{\"arrayValue\":{\"values\":["
                + "{\"stringValue\":\"sku-1\"},{\"intValue\":\"3\"}]}}}"
                + "]}}}"), json);
        assertTrue(json.contains("{\"key\":\"payload\",\"value\":{\"bytesValue\":\"AQID\"}}"),
                "bytes are base64-encoded: " + json);
        assertTrue(json.contains("{\"key\":\"mixed\",\"value\":{\"arrayValue\":{\"values\":["
                + "{\"stringValue\":\"a\"},{\"boolValue\":false}]}}}"), json);
        assertTrue(json.contains("{\"key\":\"nothing\",\"value\":{}}"), "an empty value is an empty AnyValue: " + json);
    }
}
