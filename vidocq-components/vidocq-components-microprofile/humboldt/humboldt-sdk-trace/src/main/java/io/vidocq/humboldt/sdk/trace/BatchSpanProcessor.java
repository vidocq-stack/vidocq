package io.vidocq.humboldt.sdk.trace;

import io.opentelemetry.context.Context;
import io.vidocq.humboldt.sdk.common.AbstractBatchProcessor;
import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.trace.data.SpanData;
import io.vidocq.humboldt.sdk.trace.export.SpanExporter;
import io.vidocq.humboldt.sdk.trace.export.SpanProcessor;

import java.time.Duration;

/**
 * Batch span processor — délègue le squelette (queue, worker VT, scheduleDelay,
 * flush, drain on shutdown) à {@link AbstractBatchProcessor}. La logique
 * spécifique aux spans = filtrer les non-samplés avant {@code offer}.
 *
 * <p>N'exporte que les spans samplés ({@code SpanContext.isSampled() == true}).</p>
 */
public final class BatchSpanProcessor extends AbstractBatchProcessor<SpanData> implements SpanProcessor {

    private static final int DEFAULT_MAX_QUEUE = 2048;
    private static final int DEFAULT_MAX_BATCH = 512;
    private static final Duration DEFAULT_SCHEDULE = Duration.ofSeconds(5);

    private final SpanExporter exporter;

    public static Builder builder(SpanExporter exporter) {
        return new Builder(exporter);
    }

    private BatchSpanProcessor(Builder b) {
        super(
                "humboldt-batch-span-processor",
                b.maxQueueSize,
                b.maxExportBatchSize,
                b.scheduleDelay,
                batch -> b.exporter.export(batch),
                b.exporter::flush,
                () -> b.exporter.shutdown());
        this.exporter = b.exporter;
    }

    @Override
    public void onStart(Context parentContext, ReadableSpan span) {
        // no-op
    }

    @Override
    public void onEnd(ReadableSpan span) {
        if (!span.getSpanContext().isSampled()) return;
        offer(span.toSpanData());
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
        return flushBase();
    }

    @Override
    public CompletableResultCode shutdown() {
        return shutdownBase();
    }

    public static final class Builder {
        private final SpanExporter exporter;
        private int maxQueueSize = DEFAULT_MAX_QUEUE;
        private int maxExportBatchSize = DEFAULT_MAX_BATCH;
        private Duration scheduleDelay = DEFAULT_SCHEDULE;

        Builder(SpanExporter exporter) {
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

        public BatchSpanProcessor build() {
            return new BatchSpanProcessor(this);
        }
    }
}
