package io.vidocq.humboldt.sdk.log.export;

import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.log.data.LogRecordData;

/**
 * Hook appelé par le SDK à chaque émission d'un {@link LogRecordData}.
 *
 * <p>Implémentations standard :</p>
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
