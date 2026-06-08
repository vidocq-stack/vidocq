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
import java.util.Collection;
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
    private volatile CompletableResultCode pendingFlush;

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
            CompletableResultCode rc = new CompletableResultCode();
            pendingFlush = rc;
            flushLock.notifyAll();
            return rc;
        }
    }

    @Override
    public CompletableResultCode shutdown() {
        if (!running.compareAndSet(true, false)) return CompletableResultCode.ofSuccess();
        synchronized (flushLock) {
            flushLock.notifyAll();
        }
        try {
            worker.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return exporter.shutdown();
    }

    private void workerLoop() {
        long lastCollectNanos = System.nanoTime();
        while (running.get()) {
            long now = System.nanoTime();
            long waitNanos = scheduleDelayNanos - (now - lastCollectNanos);
            CompletableResultCode requested = null;
            synchronized (flushLock) {
                if (waitNanos > 0 && pendingFlush == null && running.get()) {
                    try {
                        TimeUnit.NANOSECONDS.timedWait(flushLock, waitNanos);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                requested = pendingFlush;
                pendingFlush = null;
            }
            doCollectAndExport(requested);
            lastCollectNanos = System.nanoTime();
        }
        // Final drain
        doCollectAndExport(null);
    }

    private void doCollectAndExport(CompletableResultCode requested) {
        if (registration == null) {
            if (requested != null) requested.succeed();
            return;
        }
        try {
            Collection<MetricData> metrics = registration.collectAllMetrics();
            if (metrics.isEmpty()) {
                if (requested != null) requested.succeed();
                return;
            }
            CompletableResultCode rc = exporter.export(metrics);
            if (requested != null) {
                rc.whenComplete(() -> {
                    if (rc.isSuccess()) requested.succeed(); else requested.fail();
                });
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to collect/export metrics", e);
            if (requested != null) requested.fail();
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
