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
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.vidocq.humboldt.exporter.otlp.http.internal.OtlpJsonEncoder;
import io.vidocq.humboldt.exporter.otlp.http.internal.OtlpJsonLogEncoder;
import io.vidocq.humboldt.exporter.otlp.http.internal.OtlpJsonMetricEncoder;
import io.vidocq.humboldt.exporter.otlp.http.internal.OtlpProtoLogEncoder;
import io.vidocq.humboldt.exporter.otlp.http.internal.OtlpProtoMetricEncoder;
import io.vidocq.humboldt.exporter.otlp.http.internal.OtlpProtoSpanEncoder;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.log.data.LogRecordData;
import io.vidocq.humboldt.sdk.metric.data.AggregationTemporality;
import io.vidocq.humboldt.sdk.metric.data.DoublePointData;
import io.vidocq.humboldt.sdk.metric.data.HistogramPointData;
import io.vidocq.humboldt.sdk.metric.data.InstrumentType;
import io.vidocq.humboldt.sdk.metric.data.LongPointData;
import io.vidocq.humboldt.sdk.metric.data.MetricData;
import io.vidocq.humboldt.sdk.trace.data.EventData;
import io.vidocq.humboldt.sdk.trace.data.LinkData;
import io.vidocq.humboldt.sdk.trace.data.SpanData;
import io.vidocq.humboldt.sdk.trace.data.StatusData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.vidocq.humboldt.exporter.otlp.http.OtlpProtoSchema.LOGS_REQUEST;
import static io.vidocq.humboldt.exporter.otlp.http.OtlpProtoSchema.METRICS_REQUEST;
import static io.vidocq.humboldt.exporter.otlp.http.OtlpProtoSchema.TRACES_REQUEST;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The OTLP/protobuf encoders, checked against the official opentelemetry-proto messages: the bytes are read with
 * the official schema ({@link OtlpProtoSchema}) and must carry exactly what the OTLP/JSON encoders write for the
 * same batch.
 */
class OtlpProtoEncoderTest {

    private static final SpanContext CTX = SpanContext.create(
            "0123456789abcdef0123456789abcdef", "0123456789abcdef",
            TraceFlags.getSampled(), TraceState.getDefault());
    private static final SpanContext PARENT = SpanContext.create(
            "0123456789abcdef0123456789abcdef", "abcdef9876543210",
            TraceFlags.getSampled(), TraceState.getDefault());
    private static final SpanContext LINKED = SpanContext.create(
            "11111111111111111111111111111111", "2222222222222222",
            TraceFlags.getDefault(), TraceState.getDefault());

    private static final Resource SERVICE = Resource.create(Attributes.builder()
            .put("service.name", "humboldt-proto")
            .put("host.cores", 8L)
            .build(), "https://opentelemetry.io/schemas/1.27.0");
    private static final InstrumentationScope SCOPE = new InstrumentationScope(
            "io.vidocq.test", "1.2.3", "https://opentelemetry.io/schemas/1.26.0",
            Attributes.of(AttributeKey.booleanKey("scope.flag"), true));

    /** Every kind of attribute value: scalars, defaults, negatives, non-finite doubles, arrays and complex values. */
    private static final Attributes EVERY_KIND = Attributes.builder()
            .put("s", "é ✓ \"quoted\"")
            .put("s.empty", "")
            .put("b.true", true)
            .put("b.false", false)
            .put("l.zero", 0L)
            .put("l.negative", -42L)
            .put("l.max", Long.MAX_VALUE)
            .put("d", 2.5)
            .put("d.zero", 0.0)
            .put("d.nan", Double.NaN)
            .put("d.inf", Double.NEGATIVE_INFINITY)
            .put(AttributeKey.stringArrayKey("sa"), List.of("a", "", "c"))
            .put(AttributeKey.booleanArrayKey("ba"), List.of(true, false))
            .put(AttributeKey.longArrayKey("la"), List.of(1L, -1L, 0L))
            .put(AttributeKey.doubleArrayKey("da"), List.of(0.5, -0.25))
            .put(AttributeKey.valueKey("complex"), Value.of(
                    KeyValue.of("bytes", Value.of(new byte[] {0, 1, (byte) 0xFF})),
                    KeyValue.of("list", Value.of(Value.of("x"), Value.of(7L), Value.of(false))),
                    KeyValue.of("empty", Value.empty())))
            .build();

    // ------------------------------------------------------------------ traces

    @Test
    void a_span_batch_decodes_with_the_official_schema_as_its_json_encoding() {
        List<SpanData> spans = List.of(
                new SpanData(CTX, PARENT, "GET /api", SpanKind.SERVER, 1_000_000_000L, 1_500_000_000L,
                        EVERY_KIND,
                        List.of(new EventData(1_200_000_000L, "step", Attributes.of(
                                AttributeKey.stringKey("phase"), "init")), new EventData(1_300_000_000L, "bare",
                                Attributes.empty())),
                        List.of(new LinkData(LINKED, Attributes.of(AttributeKey.stringKey("rel"), "follow"))),
                        StatusData.error("boom"), SERVICE, SCOPE),
                new SpanData(CTX, null, "root", SpanKind.INTERNAL, 1L, 2L, Attributes.empty(), List.of(),
                        List.of(), StatusData.unset(), SERVICE, SCOPE),
                new SpanData(CTX, SpanContext.getInvalid(), "other-scope", SpanKind.CLIENT, 3L, 4L,
                        Attributes.empty(), List.of(), List.of(), StatusData.ok(), SERVICE,
                        InstrumentationScope.of("io.vidocq.other")),
                new SpanData(CTX, null, "other-resource", SpanKind.PRODUCER, 5L, 6L, Attributes.empty(), List.of(),
                        List.of(), StatusData.unset(), Resource.empty(), InstrumentationScope.of("")),
                new SpanData(CTX, null, "consumer", SpanKind.CONSUMER, 7L, 8L, Attributes.empty(), List.of(),
                        List.of(), StatusData.unset(), Resource.empty(), InstrumentationScope.of("")));

        Map<String, Object> decoded = OtlpProtoSchema.decode(TRACES_REQUEST, OtlpProtoSpanEncoder.encode(spans));

        assertEquals(OtlpProtoSchema.fromJson(TRACES_REQUEST, OtlpJsonEncoder.encode(spans)), decoded);
        assertEquals(2, ((List<?>) decoded.get("resourceSpans")).size(), decoded::toString);
    }

    @Test
    void ids_are_raw_bytes_and_times_are_fixed64() {
        SpanData span = new SpanData(CTX, null, "n", SpanKind.INTERNAL, 0x0102030405060708L, 0x1112131415161718L,
                Attributes.empty(), List.of(), List.of(), StatusData.unset(), Resource.empty(),
                InstrumentationScope.of(""));

        byte[] bytes = OtlpProtoSpanEncoder.encode(List.of(span));

        assertTrue(contains(bytes, concat(new byte[] {0x0A, 16}, CTX.getTraceIdBytes())), "trace_id = 1, 16 bytes");
        assertTrue(contains(bytes, concat(new byte[] {0x12, 8}, CTX.getSpanIdBytes())), "span_id = 2, 8 bytes");
        assertTrue(contains(bytes, new byte[] {0x39, 8, 7, 6, 5, 4, 3, 2, 1}), "start_time_unix_nano = 7, fixed64");
        assertTrue(contains(bytes, new byte[] {0x41, 0x18, 0x17, 0x16, 0x15, 0x14, 0x13, 0x12, 0x11}),
                "end_time_unix_nano = 8, fixed64");
    }

    @Test
    void an_empty_batch_is_an_empty_request() {
        assertArrayEquals(new byte[0], OtlpProtoSpanEncoder.encode(List.of()));
        assertArrayEquals(new byte[0], OtlpProtoMetricEncoder.encode(List.of()));
        assertArrayEquals(new byte[0], OtlpProtoLogEncoder.encode(List.of()));
    }

    @Test
    void a_large_batch_keeps_its_lengths_right() {
        String longName = "x".repeat(20_000);
        List<SpanData> spans = new java.util.ArrayList<>();
        for (int i = 0; i < 300; i++) {
            spans.add(new SpanData(CTX, PARENT, longName + i, SpanKind.SERVER, i, i + 1L, EVERY_KIND, List.of(),
                    List.of(), StatusData.ok(), SERVICE, SCOPE));
        }

        assertEquals(OtlpProtoSchema.fromJson(TRACES_REQUEST, OtlpJsonEncoder.encode(spans)),
                OtlpProtoSchema.decode(TRACES_REQUEST, OtlpProtoSpanEncoder.encode(spans)));
    }

    // ------------------------------------------------------------------ metrics

    @Test
    void a_metric_batch_decodes_with_the_official_schema_as_its_json_encoding() {
        Attributes pointAttributes = Attributes.of(AttributeKey.stringKey("route"), "/api");
        List<MetricData> metrics = List.of(
                metric("requests", InstrumentType.COUNTER, AggregationTemporality.CUMULATIVE, true,
                        new LongPointData(1L, 2L, pointAttributes, 7L),
                        new LongPointData(1L, 2L, Attributes.empty(), 0L)),
                metric("queue", InstrumentType.UP_DOWN_COUNTER, AggregationTemporality.DELTA, false,
                        new LongPointData(1L, 2L, Attributes.empty(), -3L)),
                metric("ratio", InstrumentType.OBSERVABLE_COUNTER, AggregationTemporality.CUMULATIVE, true,
                        new DoublePointData(1L, 2L, Attributes.empty(), 0.0)),
                metric("temperature", InstrumentType.GAUGE, AggregationTemporality.CUMULATIVE, false,
                        new DoublePointData(1L, 2L, pointAttributes, -1.25),
                        new DoublePointData(1L, 2L, Attributes.empty(), Double.NaN)),
                metric("threads", InstrumentType.OBSERVABLE_GAUGE, AggregationTemporality.CUMULATIVE, false,
                        new LongPointData(1L, 2L, Attributes.empty(), 42L)),
                metric("latency", InstrumentType.HISTOGRAM, AggregationTemporality.CUMULATIVE, false,
                        new HistogramPointData(1L, 2L, pointAttributes, 12.5, 3L, 0.5, 10.0,
                                List.of(1.0, 5.0), List.of(1L, 1L, 1L)),
                        new HistogramPointData(1L, 2L, Attributes.empty(), 0.0, 0L, Double.NaN, Double.NaN,
                                List.of(), List.of(0L))));

        Map<String, Object> decoded = OtlpProtoSchema.decode(METRICS_REQUEST, OtlpProtoMetricEncoder.encode(metrics));

        assertEquals(OtlpProtoSchema.fromJson(METRICS_REQUEST, OtlpJsonMetricEncoder.encode(metrics)), decoded);
    }

    @Test
    void a_metric_whose_points_do_not_match_its_kind_is_rejected() {
        MetricData mismatched = metric("m", InstrumentType.HISTOGRAM, AggregationTemporality.CUMULATIVE, false,
                new LongPointData(1L, 2L, Attributes.empty(), 7L));
        MetricData histogramInASum = metric("s", InstrumentType.COUNTER, AggregationTemporality.CUMULATIVE, true,
                new HistogramPointData(1L, 2L, Attributes.empty(), 1.0, 1L, 1.0, 1.0, List.of(), List.of(1L)));

        List<MetricData> mismatchedBatch = List.of(mismatched);
        List<MetricData> histogramInASumBatch = List.of(histogramInASum);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> OtlpProtoMetricEncoder.encode(mismatchedBatch));
        assertTrue(e.getMessage().contains("'m'") && e.getMessage().contains("HISTOGRAM"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> OtlpProtoMetricEncoder.encode(histogramInASumBatch));
    }

    private static MetricData metric(String name, InstrumentType type, AggregationTemporality temporality,
                                     boolean monotonic, io.vidocq.humboldt.sdk.metric.data.PointData... points) {
        return new MetricData(SERVICE, SCOPE, name, "the " + name, "1", type, temporality, monotonic,
                List.of(points));
    }

    // ------------------------------------------------------------------ logs

    @Test
    void a_log_batch_decodes_with_the_official_schema_as_its_json_encoding() {
        List<LogRecordData> records = List.of(
                new LogRecordData(SERVICE, SCOPE, 1_000L, 2_000L, CTX, Severity.WARN, "WARN", "plain body",
                        EVERY_KIND, "app.event"),
                new LogRecordData(SERVICE, SCOPE, 0L, 3_000L, LINKED, Severity.UNDEFINED_SEVERITY_NUMBER, "", null,
                        Attributes.empty(), "", Value.of(KeyValue.of("k", Value.of(1.5)))),
                new LogRecordData(SERVICE, SCOPE, 4_000L, 5_000L, SpanContext.getInvalid(), Severity.INFO, "INFO",
                        null, Attributes.empty(), "", Value.of("")),
                new LogRecordData(Resource.empty(), InstrumentationScope.of("jul"), 6_000L, 7_000L,
                        SpanContext.getInvalid(), Severity.ERROR, "SEVERE", null, Attributes.empty(), "",
                        Value.of(new byte[] {9, 8, 7})));

        Map<String, Object> decoded = OtlpProtoSchema.decode(LOGS_REQUEST, OtlpProtoLogEncoder.encode(records));

        assertEquals(OtlpProtoSchema.fromJson(LOGS_REQUEST, OtlpJsonLogEncoder.encode(records)), decoded);
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
