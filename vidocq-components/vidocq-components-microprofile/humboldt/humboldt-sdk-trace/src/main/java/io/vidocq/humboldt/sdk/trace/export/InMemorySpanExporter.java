package io.vidocq.humboldt.sdk.trace.export;

import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.common.InMemoryExporterBase;
import io.vidocq.humboldt.sdk.trace.data.SpanData;

import java.util.Collection;
import java.util.List;

/**
 * Exporter that accumulates spans in memory — for tests/debugging.
 * Delegates the shared skeleton to {@link InMemoryExporterBase}.
 */
public final class InMemorySpanExporter extends InMemoryExporterBase<SpanData> implements SpanExporter {

    public static InMemorySpanExporter create() {
        return new InMemorySpanExporter();
    }

    /** Historical alias for {@link #getCollected()}, kept for test backward compatibility. */
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
