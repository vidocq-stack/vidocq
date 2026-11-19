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
package io.vidocq.heisenberg.cdi.internal;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.Snapshot;
import org.eclipse.microprofile.metrics.Tag;

/**
 * Test-only MicroProfile Metrics registry: implements, through a {@link Proxy}, the
 * {@code counter}, {@code histogram} and {@code gauge} lookups that {@link DiracFtMetrics} uses,
 * keyed by {@link MetricID} as a real registry is.
 */
final class RecordingMetricRegistry {

    private final Map<MetricID, CountingCounter> counters = new ConcurrentHashMap<>();
    private final Map<MetricID, RecordingHistogram> histograms = new ConcurrentHashMap<>();
    private final Map<MetricID, Supplier<Number>> gauges = new ConcurrentHashMap<>();
    private final Map<String, String> units = new ConcurrentHashMap<>();

    final MetricRegistry registry = (MetricRegistry) Proxy.newProxyInstance(
            MetricRegistry.class.getClassLoader(), new Class<?>[]{MetricRegistry.class}, (self, method, args) -> {
                switch (method.getName()) {
                    case "counter":
                        return counters.computeIfAbsent(id(args, 1), _ -> new CountingCounter());
                    case "histogram":
                        return histograms.computeIfAbsent(id(args, 1), _ -> new RecordingHistogram());
                    case "gauge":
                        return gauge(args);
                    case "toString":
                        return "RecordingMetricRegistry";
                    default:
                        throw new UnsupportedOperationException(method.toString());
                }
            });

    @SuppressWarnings("unchecked")
    private Object gauge(Object[] args) {
        MetricID id = id(args, 3);
        Object state = args[1];
        Function<Object, Number> function = (Function<Object, Number>) args[2];
        gauges.put(id, () -> function.apply(state));
        return null;
    }

    /** The metric id from a {@code (name or metadata, ..., Tag...)} lookup; tags are at {@code tagsIndex}. */
    private MetricID id(Object[] args, int tagsIndex) {
        String name;
        if (args[0] instanceof Metadata metadata) {
            name = metadata.getName();
            metadata.unit().ifPresent(unit -> units.put(name, unit));
        } else {
            name = (String) args[0];
        }
        Tag[] tags = args.length > tagsIndex ? (Tag[]) args[tagsIndex] : new Tag[0];
        return new MetricID(name, tags);
    }

    static MetricID id(String name, String... tags) {
        Tag[] array = new Tag[tags.length / 2];
        for (int i = 0; i < array.length; i++) {
            array[i] = new Tag(tags[2 * i], tags[2 * i + 1]);
        }
        return new MetricID(name, array);
    }

    long count(MetricID id) {
        CountingCounter counter = counters.get(id);
        return counter == null ? -1 : counter.getCount();
    }

    boolean hasCounter(MetricID id) {
        return counters.containsKey(id);
    }

    List<Long> histogramValues(MetricID id) {
        RecordingHistogram histogram = histograms.get(id);
        return histogram == null ? null : List.copyOf(histogram.values);
    }

    Number gaugeValue(MetricID id) {
        Supplier<Number> gauge = gauges.get(id);
        return gauge == null ? null : gauge.get();
    }

    long counterCount() {
        return counters.size();
    }

    String unit(String name) {
        return units.get(name);
    }

    private static final class CountingCounter implements Counter {
        private final AtomicLong count = new AtomicLong();

        @Override
        public void inc() {
            count.incrementAndGet();
        }

        @Override
        public void inc(long n) {
            count.addAndGet(n);
        }

        @Override
        public long getCount() {
            return count.get();
        }
    }

    private static final class RecordingHistogram implements Histogram {
        private final List<Long> values = new CopyOnWriteArrayList<>();

        @Override
        public void update(int value) {
            values.add((long) value);
        }

        @Override
        public void update(long value) {
            values.add(value);
        }

        @Override
        public long getCount() {
            return values.size();
        }

        @Override
        public long getSum() {
            return values.stream().mapToLong(Long::longValue).sum();
        }

        @Override
        public Snapshot getSnapshot() {
            throw new UnsupportedOperationException("not needed by the tests");
        }
    }
}
