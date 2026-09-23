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
package io.vidocq.runtime.extensions.microprofile.dirac;

import io.vidocq.runtime.extensions.microprofile.dirac.MetricsScope.Entry;
import io.vidocq.runtime.extensions.microprofile.dirac.MetricsScope.Kind;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.devconsole.Unit;
import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.Timer;

import java.time.Duration;

/**
 * Writes the live values of the {@code metrics} panel: one group per registry, application first.
 *
 * <p>In each group, the number of {@code counters}, {@code timers}, {@code histograms} and {@code gauges}, then one
 * value per metric, under the key {@link MetricKeys} gives it:
 * <ul>
 *   <li>a counter: its count, as a counter;</li>
 *   <li>a timer: its count, as a counter, and under {@code <key>.mean} its mean duration, its elapsed time divided
 *       by its count. No max, no percentile: Dirac's {@link Timer#getSnapshot()} copies and sorts every value its
 *       reservoir holds, too much for a poll that should take well under a millisecond, so the panel reads
 *       {@link Timer#getCount()} and {@link Timer#getElapsedTime()} only;</li>
 *   <li>a histogram: how many values it recorded, as a counter, for the same reason;</li>
 *   <li>a gauge: nothing. Its value is application code, which may do I/O or take a lock.</li>
 * </ul>
 *
 * <p>The console keeps 64 values per group. The group's own five, the four counts and {@code omitted}, leave 59 for
 * its metrics: past them, the rest of the group is left out, in order, a timer whole or not at all, and
 * {@code omitted} says how many metrics were.
 */
final class MetricsPanel {

    /** The values the console keeps per group. */
    static final int VALUES_PER_GROUP = 64;
    /** What is left for the metrics, once the group's own values are written. */
    static final int METRIC_VALUES = VALUES_PER_GROUP - MetricsScope.RESERVED.size();
    /** The reason a timer has no mean. */
    static final String NO_CALL_YET = "no call yet";

    private MetricsPanel() {}

    /**
     * Writes a sample of the three registries.
     *
     * @param registries the registries of the existing producer
     * @param out        where the values go
     */
    static void write(DiracRegistries registries, PanelSample out) {
        for (MetricsScope scope : registries.read()) {
            write(scope, out.group(scope.scope()));
        }
    }

    private static void write(MetricsScope scope, PanelSample group) {
        group.gauge(MetricsScope.COUNTERS, scope.count(Kind.COUNTER), Unit.COUNT)
                .gauge(MetricsScope.TIMERS, scope.count(Kind.TIMER), Unit.COUNT)
                .gauge(MetricsScope.HISTOGRAMS, scope.count(Kind.HISTOGRAM), Unit.COUNT)
                .gauge(MetricsScope.GAUGES, scope.gauges().size(), Unit.COUNT);
        int written = 0;
        int omitted = 0;
        for (Entry entry : scope.sampled()) {
            if (omitted > 0 || written + entry.kind().values > METRIC_VALUES) {
                omitted++;
                continue;
            }
            write(entry, group);
            written += entry.kind().values;
        }
        if (omitted > 0) {
            group.text(MetricsScope.OMITTED, omitted + (omitted == 1 ? " metric" : " metrics")
                    + " left out: the console shows " + VALUES_PER_GROUP + " values per group");
        }
    }

    private static void write(Entry entry, PanelSample group) {
        switch (entry.kind()) {
            case COUNTER -> group.counter(entry.key(), ((Counter) entry.metric()).getCount(), Unit.COUNT);
            case HISTOGRAM -> group.counter(entry.key(), ((Histogram) entry.metric()).getCount(), Unit.COUNT);
            case TIMER -> {
                Timer timer = (Timer) entry.metric();
                long count = timer.getCount();
                Duration elapsed = timer.getElapsedTime();
                group.counter(entry.key(), count, Unit.COUNT);
                if (count > 0 && elapsed != null) {
                    group.duration(entry.key() + MetricsScope.MEAN, elapsed.dividedBy(count));
                } else {
                    group.absent(entry.key() + MetricsScope.MEAN, NO_CALL_YET);
                }
            }
        }
    }
}
