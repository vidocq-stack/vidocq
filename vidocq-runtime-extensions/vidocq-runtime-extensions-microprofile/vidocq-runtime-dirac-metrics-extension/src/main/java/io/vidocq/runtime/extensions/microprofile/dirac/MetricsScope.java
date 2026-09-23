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

import org.eclipse.microprofile.metrics.Metric;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What one registry holds, as the {@code metrics} section and panel show it: its counters, timers and histograms,
 * each with its key, then its gauges, by name only.
 *
 * <p>It reads the registry's maps and nothing else. A gauge's {@link org.eclipse.microprofile.metrics.Gauge#getValue}
 * runs application code, which may do I/O or take a lock, so a gauge is counted and named, never called.
 *
 * @param scope   the registry scope, such as {@code application}
 * @param sampled the counters, then the timers, then the histograms, each kind in {@link MetricID} order
 * @param gauges  the gauges, in {@link MetricID} order
 */
record MetricsScope(String scope, List<Entry> sampled, List<MetricID> gauges) {

    /** Each group writes these keys itself: no metric takes them. */
    static final String COUNTERS = "counters";
    static final String TIMERS = "timers";
    static final String HISTOGRAMS = "histograms";
    static final String GAUGES = "gauges";
    static final String OMITTED = "omitted";
    static final Set<String> RESERVED = Set.of(COUNTERS, TIMERS, HISTOGRAMS, GAUGES, OMITTED);

    /** The suffix of a timer's mean, after its key, which holds its count. */
    static final String MEAN = ".mean";

    /** The kinds of metrics the panel samples, and how many values each writes. */
    enum Kind {
        COUNTER(1),
        TIMER(2),
        HISTOGRAM(1);

        final int values;

        Kind(int values) {
            this.values = values;
        }
    }

    /**
     * One sampled metric.
     *
     * @param id     its name and tags
     * @param key    the key the panel writes it under; a timer's mean is under {@code key + ".mean"}
     * @param kind   what it is
     * @param metric the metric, read by the panel
     */
    record Entry(MetricID id, String key, Kind kind, Metric metric) {}

    /**
     * Reads a registry.
     *
     * @param scope    its scope, such as {@code application}
     * @param registry the registry
     * @return what it holds, its keys allocated
     */
    static MetricsScope read(String scope, MetricRegistry registry) {
        MetricKeys keys = new MetricKeys(RESERVED);
        List<Entry> sampled = new ArrayList<>();
        add(sampled, keys, Kind.COUNTER, registry.getCounters());
        add(sampled, keys, Kind.TIMER, registry.getTimers());
        add(sampled, keys, Kind.HISTOGRAM, registry.getHistograms());
        return new MetricsScope(scope, List.copyOf(sampled), List.copyOf(registry.getGauges().keySet()));
    }

    private static void add(List<Entry> sampled, MetricKeys keys, Kind kind, Map<MetricID, ? extends Metric> metrics) {
        for (Map.Entry<MetricID, ? extends Metric> metric : metrics.entrySet()) {
            String key = kind == Kind.TIMER ? keys.allocate(metric.getKey(), MEAN) : keys.allocate(metric.getKey());
            sampled.add(new Entry(metric.getKey(), key, kind, metric.getValue()));
        }
    }

    /** How many metrics of a kind the registry holds. */
    long count(Kind kind) {
        return sampled.stream().filter(entry -> entry.kind() == kind).count();
    }

    /** Every metric of the registry, gauges included. */
    int size() {
        return sampled.size() + gauges.size();
    }

    /** A metric's name, then its tags between braces when it has any: {@code checkout{shop=eu}}. */
    static String describe(MetricID id) {
        Map<String, String> tags = id.getTags();
        if (tags.isEmpty()) {
            return id.getName();
        }
        return tags.entrySet().stream()
                .map(tag -> tag.getKey() + "=" + tag.getValue())
                .collect(Collectors.joining(",", id.getName() + "{", "}"));
    }
}
