package io.vidocq.humboldt.exporter.otlp.http;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.vidocq.humboldt.exporter.otlp.http.internal.OtlpJsonEncoder;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.trace.data.EventData;
import io.vidocq.humboldt.sdk.trace.data.InstrumentationScope;
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
        assertTrue(json.contains("\"kind\":2"), "SpanKind.SERVER doit être encodé en 2");
        assertTrue(json.contains("\"startTimeUnixNano\":\"1000000000\""));
        assertTrue(json.contains("\"endTimeUnixNano\":\"1500000000\""));
        assertTrue(json.contains("\"code\":1"), "StatusCode.OK doit être encodé en 1");
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
}
