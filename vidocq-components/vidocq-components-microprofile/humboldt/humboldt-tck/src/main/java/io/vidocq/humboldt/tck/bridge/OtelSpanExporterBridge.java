package io.vidocq.humboldt.tck.bridge;

import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.vidocq.humboldt.sdk.common.CompletableResultCode;

import java.util.Collection;

/**
 * Adapts an {@link SpanExporter OTel SDK SpanExporter} (for example the one provided
 * by {@code org.eclipse.microprofile.telemetry.tracing.tck.exporter.InMemorySpanExporter})
 * into an {@link io.vidocq.humboldt.sdk.trace.export.SpanExporter Humboldt SpanExporter}
 * compatible with the Humboldt trace pipeline.
 *
 * <p>For each batch received from Humboldt, converts
 * {@link io.vidocq.humboldt.sdk.trace.data.SpanData} into
 * {@link io.opentelemetry.sdk.trace.data.SpanData} via {@link SpanDataMapper}
 * and then delegates to the underlying OTel exporter.</p>
 *
 * <p>Humboldt {@link CompletableResultCode} and OTel
 * {@link io.opentelemetry.sdk.common.CompletableResultCode CompletableResultCode}
 * are semantically equivalent — the conversion is performed by
 * {@link #toHumboldt(io.opentelemetry.sdk.common.CompletableResultCode)}.</p>
 */
public final class OtelSpanExporterBridge implements io.vidocq.humboldt.sdk.trace.export.SpanExporter {

    private final SpanExporter delegate;

    public OtelSpanExporterBridge(SpanExporter delegate) {
        if (delegate == null) throw new NullPointerException("delegate");
        this.delegate = delegate;
    }

    @Override
    public CompletableResultCode export(Collection<io.vidocq.humboldt.sdk.trace.data.SpanData> spans) {
        var mapped = spans.stream().map(SpanDataMapper::toOtel).toList();
        return toHumboldt(delegate.export(mapped));
    }

    @Override
    public CompletableResultCode flush() {
        return toHumboldt(delegate.flush());
    }

    @Override
    public CompletableResultCode shutdown() {
        return toHumboldt(delegate.shutdown());
    }

    private static CompletableResultCode toHumboldt(io.opentelemetry.sdk.common.CompletableResultCode otel) {
        CompletableResultCode out = new CompletableResultCode();
        otel.whenComplete(() -> {
            if (otel.isSuccess()) out.succeed();
            else out.fail();
        });
        return out;
    }
}
