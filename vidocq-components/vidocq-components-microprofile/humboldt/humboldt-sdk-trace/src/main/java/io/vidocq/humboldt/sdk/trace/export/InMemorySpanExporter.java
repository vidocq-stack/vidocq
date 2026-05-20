package io.vidocq.humboldt.sdk.trace.export;

import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.common.InMemoryExporterBase;
import io.vidocq.humboldt.sdk.trace.data.SpanData;

import java.util.Collection;
import java.util.List;

/**
 * Exporter qui accumule les spans en mémoire — pour tests/debug.
 * Délègue à {@link InMemoryExporterBase} le squelette mutualisé.
 */
public final class InMemorySpanExporter extends InMemoryExporterBase<SpanData> implements SpanExporter {

    public static InMemorySpanExporter create() {
        return new InMemorySpanExporter();
    }

    /** Alias historique de {@link #getCollected()}, conservé pour rétro-compat des tests. */
    public List<SpanData> getFinishedSpans() {
        return getCollected();
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        return addAll(spans);
    }

    @Override
    public CompletableResultCode flush() {
        return flushBase();
    }

    @Override
    public CompletableResultCode shutdown() {
        return shutdownBase();
    }
}
