package io.vidocq.humboldt.sdk.trace;

import io.opentelemetry.context.Context;
import io.vidocq.humboldt.sdk.trace.export.CompletableResultCode;
import io.vidocq.humboldt.sdk.trace.export.SpanExporter;
import io.vidocq.humboldt.sdk.trace.export.SpanProcessor;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Export synchrone — chaque {@code onEnd()} déclenche immédiatement
 * {@code exporter.export([spanData])}.
 *
 * <p>Adapté aux exporters fiables et rapides (in-memory, logging). Pour les
 * exporters réseau, préférer {@link BatchSpanProcessor}.</p>
 *
 * <p>N'exporte que les spans samplés ({@code SpanContext.isSampled() == true}).</p>
 */
public final class SimpleSpanProcessor implements SpanProcessor {

    private final SpanExporter exporter;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public static SimpleSpanProcessor create(SpanExporter exporter) {
        return new SimpleSpanProcessor(exporter);
    }

    private SimpleSpanProcessor(SpanExporter exporter) {
        if (exporter == null) throw new NullPointerException("exporter");
        this.exporter = exporter;
    }

    @Override
    public void onStart(Context parentContext, ReadableSpan span) {
        // no-op
    }

    @Override
    public void onEnd(ReadableSpan span) {
        if (stopped.get()) return;
        if (!span.getSpanContext().isSampled()) return;
        exporter.export(List.of(span.toSpanData()));
    }

    @Override
    public boolean isStartRequired() {
        return false;
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    @Override
    public CompletableResultCode flush() {
        return exporter.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        if (!stopped.compareAndSet(false, true)) return CompletableResultCode.ofSuccess();
        return exporter.shutdown();
    }
}
