package io.vidocq.humboldt.sdk.log.export;

import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.log.data.LogRecordData;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Exporter qui accumule les LogRecord en mémoire — exclusivement pour les
 * tests. {@code shutdown()} ne purge pas le contenu, voir {@link #reset()}.
 */
public final class InMemoryLogRecordExporter implements LogRecordExporter {

    private final List<LogRecordData> collected = new CopyOnWriteArrayList<>();
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public static InMemoryLogRecordExporter create() {
        return new InMemoryLogRecordExporter();
    }

    public List<LogRecordData> getCollected() {
        return List.copyOf(collected);
    }

    public void reset() {
        collected.clear();
    }

    @Override
    public CompletableResultCode export(Collection<LogRecordData> records) {
        if (stopped.get()) return CompletableResultCode.ofFailure();
        collected.addAll(records);
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        stopped.set(true);
        return CompletableResultCode.ofSuccess();
    }
}
