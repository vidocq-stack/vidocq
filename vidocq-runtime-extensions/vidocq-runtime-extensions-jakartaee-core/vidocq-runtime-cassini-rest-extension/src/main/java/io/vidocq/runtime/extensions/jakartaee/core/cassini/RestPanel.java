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
package io.vidocq.runtime.extensions.jakartaee.core.cassini;

import io.vidocq.cassini.spi.http.CassiniStatistics;
import io.vidocq.runtime.spi.devconsole.Chart;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.devconsole.Series;
import io.vidocq.runtime.spi.devconsole.Unit;

import java.time.Duration;
import java.util.List;

/**
 * The live half of the {@code rest} panel: what each mounted Cassini stack counted, read from
 * {@code CassiniStack.statistics()} (Vidocq/cassini#42). One group per mount, named as the report names it.
 *
 * <p>Reading the counters sums a few adders: no lock, no I/O, no instance created, well under a microsecond.
 */
final class RestPanel {

    /** What the page plots, repeated for every mount. */
    static final List<Chart> CHARTS = List.of(
            new Chart("requests", "Requests", List.of(Series.rate("requests"))),
            new Chart("responses", "Responses by status",
                    List.of(Series.rate("status.2xx"), Series.rate("status.3xx"), Series.rate("status.4xx"),
                            Series.rate("status.5xx"))),
            new Chart("in-flight", "In flight", List.of(Series.area("in-flight"))),
            new Chart("handler-time", "Time in handlers", List.of(Series.rate("handler-time"))));

    private RestPanel() {}

    static void sample(List<RestMount> mounts, PanelSample out) {
        if (mounts.isEmpty()) {
            out.absent("requests", "no REST resource mounted");
            return;
        }
        for (RestMount mount : mounts) {
            PanelSample group = out.group(mount.name());
            CassiniStatistics stats = mount.statistics();
            if (stats == null) {
                group.absent("requests", "counters off");
                continue;
            }
            long requests = stats.requests();
            long nanos = stats.totalNanos();
            group.counter("requests", requests, Unit.COUNT)
                    .gauge("in-flight", stats.inFlight(), Unit.COUNT);
            for (int statusClass = 1; statusClass <= 5; statusClass++) {
                group.counter("status." + statusClass + "xx", stats.responses(statusClass), Unit.COUNT);
            }
            // A counter of nanoseconds: the page plots its rate as the share of wall time spent in handlers.
            group.counter("handler-time", nanos, Unit.NANOS);
            if (requests == 0) {
                group.absent("mean", "no request served yet").absent("max", "no request served yet");
            } else {
                group.duration("mean", Duration.ofNanos(nanos / requests))
                        .duration("max", Duration.ofNanos(stats.maxNanos()));
            }
        }
    }
}
