package io.vidocq.humboldt.sdk.log.export;

import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.common.InMemoryExporterBase;
import io.vidocq.humboldt.sdk.log.data.LogRecordData;

import java.util.Collection;

/**
 * Exporter qui accumule les LogRecord en mémoire — pour tests.
 * Délègue à {@link InMemoryExporterBase} le squelette mutualisé.
 */
public final class InMemoryLogRecordExporter extends InMemoryExporterBase<LogRecordData> implements LogRecordExporter {

    public static InMemoryLogRecordExporter create() {
        return new InMemoryLogRecordExporter();
    }

    @Override
    public CompletableResultCode export(Collection<LogRecordData> records) {
        return addAll(records);
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
