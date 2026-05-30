package io.vidocq.humboldt.sdk.common;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared in-memory exporter skeleton for the 3 signals (spans, metrics, logs).
 *
 * <p>Concrete subclasses implement the specific SDK interface
 * ({@code SpanExporter}, {@code MetricExporter}, {@code LogRecordExporter})
 * and delegate {@code export(...)} to {@link #addAll(Collection)}.</p>
 *
 * <p>Intentionally, {@link #shutdown()} does NOT clear {@link #collected} —
 * tests that inspect the exporter via try-with-resources on the provider can
 * therefore read the drained data after {@code close()}. Use
 * {@link #reset()} to clear it explicitly.</p>
 */
public abstract class InMemoryExporterBase<T> {

    private final List<T> collected = new CopyOnWriteArrayList<>();
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /**
     * @return an immutable snapshot of the elements collected since startup
     *         (or since the last {@link #reset()}).
     */
    public final List<T> getCollected() {
        return List.copyOf(collected);
    }

    /** Clears the list — does not affect the {@code stopped} state. */
    public final void reset() {
        collected.clear();
    }

    /**
     * To be called from subclass {@code export(...)} implementations.
     *
     * @return {@link CompletableResultCode#ofFailure()} if already shut down,
     *         otherwise {@link CompletableResultCode#ofSuccess()}.
     */
    protected final CompletableResultCode addAll(Collection<T> items) {
        if (stopped.get()) return CompletableResultCode.ofFailure();
        collected.addAll(items);
        return CompletableResultCode.ofSuccess();
    }

    public final CompletableResultCode flushBase() {
        return CompletableResultCode.ofSuccess();
    }

    public final CompletableResultCode shutdownBase() {
        stopped.set(true);
        return CompletableResultCode.ofSuccess();
    }
}
