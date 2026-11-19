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
package io.vidocq.humboldt.sdk.metric;

import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.metric.data.MetricData;
import io.vidocq.humboldt.sdk.metric.export.MetricExporter;
import io.vidocq.humboldt.sdk.metric.export.MetricReader;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Push-based reader — periodically triggers {@code collectAllMetrics()} on the
 * SdkMeterProvider it was registered with, then sends the result to the {@link MetricExporter}.
 *
 * <p>Worker on a virtual thread ({@code Thread.ofVirtual()}), no carrier-thread
 * pinning (see JEP 444). Configurable {@code scheduleDelay} (default 60s).</p>
 */
public final class PeriodicMetricReader implements MetricReader {

    private static final Logger LOG = System.getLogger(PeriodicMetricReader.class.getName());
    private static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(60);

    private final MetricExporter exporter;
    private final long scheduleDelayNanos;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Object flushLock = new Object();
    private final Thread worker;
    private volatile CollectionRegistration registration;
    /** The flushes asked for and not served yet; guarded by {@link #flushLock} (BUG-20261029-04). */
    private final List<CompletableResultCode> flushRequests = new ArrayList<>();

    public static Builder builder(MetricExporter exporter) {
        return new Builder(exporter);
    }

    private PeriodicMetricReader(Builder b) {
        this.exporter = b.exporter;
        this.scheduleDelayNanos = b.interval.toNanos();
        this.worker = Thread.ofVirtual()
                .name("humboldt-periodic-metric-reader")
                .start(this::workerLoop);
    }

    @Override
    public void register(CollectionRegistration registration) {
        this.registration = registration;
    }

    @Override
    public CompletableResultCode flush() {
        if (registration == null) return CompletableResultCode.ofSuccess();
        synchronized (flushLock) {
            if (running.get()) {
                CompletableResultCode rc = new CompletableResultCode();
                flushRequests.add(rc);
                flushLock.notifyAll();
                return rc;
            }
        }
        // Shut down: the final drain exported everything, only the exporter may hold something back.
        return exporter.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        synchronized (flushLock) {
            if (!running.compareAndSet(true, false)) return CompletableResultCode.ofSuccess();
            flushLock.notifyAll();
        }
        try {
            worker.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
        return exporter.shutdown();
    }

    private void workerLoop() {
        long lastCollectNanos = System.nanoTime();
        while (running.get()) {
            long now = System.nanoTime();
            long waitNanos = scheduleDelayNanos - (now - lastCollectNanos);
            List<CompletableResultCode> requested;
            synchronized (flushLock) {
                if (waitNanos > 0 && flushRequests.isEmpty() && running.get()) {
                    try {
                        TimeUnit.NANOSECONDS.timedWait(flushLock, waitNanos);
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                }
                requested = takeFlushRequests();
            }
            // Woken up by shutdown(): leave the export to the final drain below.
            if (requested.isEmpty() && !running.get()) break;
            doCollectAndExport(requested);
            lastCollectNanos = System.nanoTime();
        }
        // Final drain, which also serves the flushes asked for while the reader shut down
        List<CompletableResultCode> requested;
        synchronized (flushLock) {
            requested = takeFlushRequests();
        }
        doCollectAndExport(requested);
    }

    /** The flushes asked for so far, removed from {@link #flushRequests}; to be called holding {@link #flushLock}. */
    private List<CompletableResultCode> takeFlushRequests() {
        if (flushRequests.isEmpty()) return List.of();
        List<CompletableResultCode> taken = List.copyOf(flushRequests);
        flushRequests.clear();
        return taken;
    }

    /** Collects and exports once, then completes every flush in {@code requested} with the export's result. */
    private void doCollectAndExport(List<CompletableResultCode> requested) {
        if (registration == null) {
            requested.forEach(CompletableResultCode::succeed);
            return;
        }
        try {
            Collection<MetricData> metrics = registration.collectAllMetrics();
            if (metrics.isEmpty()) {
                requested.forEach(CompletableResultCode::succeed);
                return;
            }
            CompletableResultCode rc = exporter.export(metrics);
            rc.whenComplete(() -> {
                for (CompletableResultCode r : requested) {
                    if (rc.isSuccess()) r.succeed();
                    else r.fail();
                }
            });
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to collect/export metrics", e);
            requested.forEach(CompletableResultCode::fail);
        }
    }

    public static final class Builder {
        private final MetricExporter exporter;
        private Duration interval = DEFAULT_INTERVAL;

        Builder(MetricExporter exporter) {
            if (exporter == null) throw new NullPointerException("exporter");
            this.exporter = exporter;
        }

        public Builder setInterval(Duration interval) {
            if (interval != null && !interval.isNegative() && !interval.isZero()) {
                this.interval = interval;
            }
            return this;
        }

        public PeriodicMetricReader build() {
            return new PeriodicMetricReader(this);
        }
    }
}
