package io.vidocq.humboldt.sdk.log.export;

import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.log.data.LogRecordData;

/**
 * Hook called by the SDK each time a {@link LogRecordData} is emitted.
 *
 * <p>Standard implementations:</p>
 * <ul>
 *   <li>{@link io.vidocq.humboldt.sdk.log.SimpleLogRecordProcessor}</li>
 *   <li>{@link io.vidocq.humboldt.sdk.log.BatchLogRecordProcessor}</li>
 * </ul>
 */
public interface LogRecordProcessor extends AutoCloseable {

    void onEmit(LogRecordData record);

    default CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    CompletableResultCode shutdown();

    @Override
    default void close() {
        shutdown().join(10, java.util.concurrent.TimeUnit.SECONDS);
    }
}
