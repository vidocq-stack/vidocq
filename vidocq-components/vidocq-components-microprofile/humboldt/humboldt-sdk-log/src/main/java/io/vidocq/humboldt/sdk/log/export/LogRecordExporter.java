package io.vidocq.humboldt.sdk.log.export;

import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.log.data.LogRecordData;

import java.util.Collection;

/**
 * Exporter de LogRecord (InMemory, Logging, OTLP/HTTP-JSON, ...).
 *
 * <p>Thread-safe — peut être partagé entre plusieurs
 * {@link LogRecordProcessor}.</p>
 */
public interface LogRecordExporter extends AutoCloseable {

    CompletableResultCode export(Collection<LogRecordData> records);

    CompletableResultCode flush();

    CompletableResultCode shutdown();

    @Override
    default void close() {
        shutdown().join(10, java.util.concurrent.TimeUnit.SECONDS);
    }
}
