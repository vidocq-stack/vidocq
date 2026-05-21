package io.vidocq.humboldt.tck.bridge;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.trace.data.SpanData;
import io.vidocq.humboldt.sdk.trace.data.StatusData;
import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class OtelSpanExporterBridgeTest {

    @Test
    public void export_humboldt_spans_arrives_in_otel_exporter() {
        InMemorySpanExporter otelSink = InMemorySpanExporter.create();
        OtelSpanExporterBridge bridge = new OtelSpanExporterBridge(otelSink);

        SpanData humboldtSpan = new SpanData(
                SpanContext.create("0123456789abcdef0123456789abcdef", "fedcba9876543210",
                        TraceFlags.getSampled(), TraceState.getDefault()),
                SpanContext.getInvalid(),
                "bridged-span",
                SpanKind.SERVER,
                1L, 2L,
                Attributes.of(AttributeKey.stringKey("http.method"), "POST"),
                List.of(), List.of(),
                StatusData.unset(),
                Resource.empty(),
                InstrumentationScope.of("io.vidocq.tck.bridge.test"));

        var result = bridge.export(List.of(humboldtSpan));
        assertTrue(result.join(2, java.util.concurrent.TimeUnit.SECONDS).isSuccess());

        var captured = otelSink.getFinishedSpanItems();
        assertEquals(captured.size(), 1);
        assertEquals(captured.getFirst().getName(), "bridged-span");
        assertEquals(captured.getFirst().getKind(), SpanKind.SERVER);
        assertEquals(captured.getFirst().getAttributes()
                .get(AttributeKey.stringKey("http.method")), "POST");
    }

    @Test
    public void flush_and_shutdown_delegate_to_otel_exporter() {
        InMemorySpanExporter otelSink = InMemorySpanExporter.create();
        OtelSpanExporterBridge bridge = new OtelSpanExporterBridge(otelSink);

        assertTrue(bridge.flush().join(2, java.util.concurrent.TimeUnit.SECONDS).isSuccess());
        assertTrue(bridge.shutdown().join(2, java.util.concurrent.TimeUnit.SECONDS).isSuccess());
    }
}
