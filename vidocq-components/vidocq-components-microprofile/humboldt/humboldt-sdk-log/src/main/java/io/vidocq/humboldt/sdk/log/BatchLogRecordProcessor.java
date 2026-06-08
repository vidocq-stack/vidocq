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
package io.vidocq.humboldt.sdk.log;

import io.vidocq.humboldt.sdk.common.AbstractBatchProcessor;
import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.log.data.LogRecordData;
import io.vidocq.humboldt.sdk.log.export.LogRecordExporter;
import io.vidocq.humboldt.sdk.log.export.LogRecordProcessor;

import java.time.Duration;

/**
 * Batch log processor — delegates the shared skeleton to
 * {@link AbstractBatchProcessor}. No filter (unlike BatchSpanProcessor,
 * which ignores non-sampled spans) — all emitted LogRecord instances are batched.
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
