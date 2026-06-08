/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.humboldt.sdk.trace;

import io.opentelemetry.context.Context;
import io.vidocq.humboldt.sdk.common.AbstractBatchProcessor;
import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.trace.data.SpanData;
import io.vidocq.humboldt.sdk.trace.export.SpanExporter;
import io.vidocq.humboldt.sdk.trace.export.SpanProcessor;

import java.time.Duration;

/**
 * Batch span processor — delegates the shared skeleton (queue, VT worker,
 * scheduleDelay, flush, drain on shutdown) to {@link AbstractBatchProcessor}.
 * Span-specific logic = filter out non-sampled spans before {@code offer}.
 *
 * <p>Exports only sampled spans ({@code SpanContext.isSampled() == true}).</p>
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
