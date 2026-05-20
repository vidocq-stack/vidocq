package io.vidocq.humboldt.sdk.trace.export;

import io.vidocq.humboldt.sdk.common.CompletableResultCode;

import io.vidocq.humboldt.sdk.trace.data.SpanData;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Exporter qui accumule les spans en mémoire. Utilisé exclusivement pour les
 * tests et le debug — thread-safe via {@link CopyOnWriteArrayList}.
 */
public final class InMemorySpanExporter implements SpanExporter {

    private final List<SpanData> finishedSpans = new CopyOnWriteArrayList<>();
    private final AtomicBoolean isStopped = new AtomicBoolean(false);

    public static InMemorySpanExporter create() {
        return new InMemorySpanExporter();
    }

    public List<SpanData> getFinishedSpans() {
        return List.copyOf(finishedSpans);
    }

    public void reset() {
        finishedSpans.clear();
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        if (isStopped.get()) return CompletableResultCode.ofFailure();
        finishedSpans.addAll(spans);
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        // Volontairement on ne purge PAS finishedSpans à shutdown : les tests
        // qui inspectent l'exporter via try-with-resources sur le provider
        // peuvent ainsi lire les spans drainés après close(). Utiliser
        // {@link #reset()} pour vider explicitement.
        isStopped.set(true);
        return CompletableResultCode.ofSuccess();
    }
}
