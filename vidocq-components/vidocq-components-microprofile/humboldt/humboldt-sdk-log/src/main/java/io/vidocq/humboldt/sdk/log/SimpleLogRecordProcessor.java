package io.vidocq.humboldt.sdk.log;

import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.log.data.LogRecordData;
import io.vidocq.humboldt.sdk.log.export.LogRecordExporter;
import io.vidocq.humboldt.sdk.log.export.LogRecordProcessor;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Export synchrone — chaque {@code onEmit()} déclenche immédiatement
 * {@code exporter.export([record])}.
 *
 * <p>Adapté aux exporters fiables et rapides (in-memory, logging stdout).
 * Pour les exporters réseau, préférer {@link BatchLogRecordProcessor}.</p>
 */
public final class SimpleLogRecordProcessor implements LogRecordProcessor {

    private final LogRecordExporter exporter;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public static SimpleLogRecordProcessor create(LogRecordExporter exporter) {
        return new SimpleLogRecordProcessor(exporter);
    }

    private SimpleLogRecordProcessor(LogRecordExporter exporter) {
        if (exporter == null) throw new NullPointerException("exporter");
        this.exporter = exporter;
    }

    @Override
    public void onEmit(LogRecordData record) {
        if (stopped.get()) return;
        exporter.export(List.of(record));
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
