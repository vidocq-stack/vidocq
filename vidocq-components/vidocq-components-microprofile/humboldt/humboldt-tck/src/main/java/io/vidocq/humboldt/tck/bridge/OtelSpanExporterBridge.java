package io.vidocq.humboldt.tck.bridge;

import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.vidocq.humboldt.sdk.common.CompletableResultCode;

import java.util.Collection;

/**
 * Adapte un {@link SpanExporter OTel SDK SpanExporter} (par exemple celui fourni
 * par {@code org.eclipse.microprofile.telemetry.tracing.tck.exporter.InMemorySpanExporter})
 * en un {@link io.vidocq.humboldt.sdk.trace.export.SpanExporter Humboldt SpanExporter}
 * compatible avec le pipeline trace Humboldt.
 *
 * <p>Pour chaque batch reçu de Humboldt, convertit les
 * {@link io.vidocq.humboldt.sdk.trace.data.SpanData} en
 * {@link io.opentelemetry.sdk.trace.data.SpanData} via {@link SpanDataMapper}
 * puis délègue à l'exporter OTel sous-jacent.</p>
 *
 * <p>{@link CompletableResultCode} Humboldt et
 * {@link io.opentelemetry.sdk.common.CompletableResultCode CompletableResultCode}
 * OTel sont sémantiquement équivalents — la conversion est faite par
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
