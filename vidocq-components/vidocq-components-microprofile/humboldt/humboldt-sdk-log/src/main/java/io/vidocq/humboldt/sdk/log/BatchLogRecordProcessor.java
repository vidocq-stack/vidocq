package io.vidocq.humboldt.sdk.log;

import io.vidocq.humboldt.sdk.common.AbstractBatchProcessor;
import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.log.data.LogRecordData;
import io.vidocq.humboldt.sdk.log.export.LogRecordExporter;
import io.vidocq.humboldt.sdk.log.export.LogRecordProcessor;

import java.time.Duration;

/**
 * Batch log processor — délègue le squelette mutualisé à
 * {@link AbstractBatchProcessor}. Pas de filtre (contrairement à BatchSpanProcessor
 * qui ignore les non-samplés) — tous les LogRecord émis sont batchés.
 */
public final class BatchLogRecordProcessor extends AbstractBatchProcessor<LogRecordData>
        implements LogRecordProcessor {

    private static final int DEFAULT_MAX_QUEUE = 2048;
    private static final int DEFAULT_MAX_BATCH = 512;
    private static final Duration DEFAULT_SCHEDULE = Duration.ofSeconds(1);

    public static Builder builder(LogRecordExporter exporter) {
        return new Builder(exporter);
    }

    private BatchLogRecordProcessor(Builder b) {
        super(
                "humboldt-batch-log-processor",
                b.maxQueueSize,
                b.maxExportBatchSize,
                b.scheduleDelay,
                batch -> b.exporter.export(batch),
                b.exporter::flush,
                () -> b.exporter.shutdown());
    }

    @Override
    public void onEmit(LogRecordData record) {
        offer(record);
    }

    @Override
    public CompletableResultCode flush() {
        return flushBase();
    }

    @Override
    public CompletableResultCode shutdown() {
        return shutdownBase();
    }

    public static final class Builder {
        private final LogRecordExporter exporter;
        private int maxQueueSize = DEFAULT_MAX_QUEUE;
        private int maxExportBatchSize = DEFAULT_MAX_BATCH;
        private Duration scheduleDelay = DEFAULT_SCHEDULE;

        Builder(LogRecordExporter exporter) {
            if (exporter == null) throw new NullPointerException("exporter");
            this.exporter = exporter;
        }

        public Builder setMaxQueueSize(int n) {
            if (n > 0) this.maxQueueSize = n;
            return this;
        }

        public Builder setMaxExportBatchSize(int n) {
            if (n > 0) this.maxExportBatchSize = n;
            return this;
        }

        public Builder setScheduleDelay(Duration d) {
            if (d != null && !d.isNegative() && !d.isZero()) this.scheduleDelay = d;
            return this;
        }

        public BatchLogRecordProcessor build() {
            return new BatchLogRecordProcessor(this);
        }
    }
}
