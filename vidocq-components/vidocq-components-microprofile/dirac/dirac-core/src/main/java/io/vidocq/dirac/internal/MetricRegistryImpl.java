package io.vidocq.dirac.internal;

import io.vidocq.dirac.api.DiracException;
import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.Gauge;
import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.Metric;
import org.eclipse.microprofile.metrics.MetricFilter;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.Tag;
import org.eclipse.microprofile.metrics.Timer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Registre de métriques Dirac — implémentation interne.
 *
 * <p>Implémentation M1 : registre thread-safe par scope, get-or-create pour les compteurs,
 * vues triées en lecture seule, metadata par nom de métrique.</p>
 */
public final class MetricRegistryImpl implements MetricRegistry {
    private final String scope;
    private final ConcurrentHashMap<MetricID, Metric> metrics = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Metadata> metadataByName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SortedSet<String>> tagKeysByName = new ConcurrentHashMap<>();

    public MetricRegistryImpl(String scope) {
        this.scope = requireMetricName(scope, "scope");
    }

    public MetricRegistryImpl(Type type) {
        this(Objects.requireNonNull(type, "type must not be null").getName());
    }

    @Override
    public Counter counter(String name) {
        return counter(name, new Tag[0]);
    }

    @Override
    public Counter counter(String name, Tag... tags) {
        return counter(defaultMetadata(name), tags);
    }

    @Override
    public Counter counter(MetricID metricID) {
        Objects.requireNonNull(metricID, "metricID must not be null");
        return counter(defaultMetadata(metricID.getName()), metricID.getTagsAsArray());
    }

    @Override
    public Counter counter(Metadata metadata) {
        return counter(metadata, new Tag[0]);
    }

    @Override
    public Counter counter(Metadata metadata, Tag... tags) {
        var normalizedMetadata = requireMetadata(metadata);
        var metricId = validatedMetricID(normalizedMetadata.getName(), tags);
        metadataByName.putIfAbsent(metricId.getName(), normalizedMetadata);
        return registerOrGet(metricId, Counter.class, CounterImpl::new);
    }

    @Override
    public <T, R extends Number> Gauge<R> gauge(String name, T object, Function<T, R> function, Tag... tags) {
        return gauge(defaultMetadata(name), object, function, tags);
    }

    @Override
    public <T, R extends Number> Gauge<R> gauge(MetricID metricID, T object, Function<T, R> function) {
        Objects.requireNonNull(metricID, "metricID must not be null");
        return registerGauge(metricID, defaultMetadata(metricID.getName()), () -> requireGaugeFunction(function).apply(object));
    }

    @Override
    public <T, R extends Number> Gauge<R> gauge(Metadata metadata, T object, Function<T, R> function, Tag... tags) {
        var normalizedMetadata = requireMetadata(metadata);
        return registerGauge(validatedMetricID(normalizedMetadata.getName(), tags), normalizedMetadata,
                () -> requireGaugeFunction(function).apply(object));
    }

    @Override
    public <T extends Number> Gauge<T> gauge(String name, Supplier<T> supplier, Tag... tags) {
        return gauge(defaultMetadata(name), supplier, tags);
    }

    @Override
    public <T extends Number> Gauge<T> gauge(MetricID metricID, Supplier<T> supplier) {
        Objects.requireNonNull(metricID, "metricID must not be null");
        return registerGauge(metricID, defaultMetadata(metricID.getName()), supplier);
    }

    @Override
    public <T extends Number> Gauge<T> gauge(Metadata metadata, Supplier<T> supplier, Tag... tags) {
        var normalizedMetadata = requireMetadata(metadata);
        return registerGauge(validatedMetricID(normalizedMetadata.getName(), tags), normalizedMetadata, supplier);
    }

    @Override
    public Histogram histogram(String name) {
        return histogram(name, new Tag[0]);
    }

    @Override
    public Histogram histogram(String name, Tag... tags) {
        return histogram(defaultMetadata(name), tags);
    }

    @Override
    public Histogram histogram(MetricID metricID) {
        Objects.requireNonNull(metricID, "metricID must not be null");
        return histogram(defaultMetadata(metricID.getName()), metricID.getTagsAsArray());
    }

    @Override
    public Histogram histogram(Metadata metadata) {
        return histogram(metadata, new Tag[0]);
    }

    @Override
    public Histogram histogram(Metadata metadata, Tag... tags) {
        var normalizedMetadata = requireMetadata(metadata);
        var metricId = validatedMetricID(normalizedMetadata.getName(), tags);
        metadataByName.putIfAbsent(metricId.getName(), normalizedMetadata);
        return registerOrGet(metricId, Histogram.class, () -> new HistogramImpl(metricId.getName(), false));
    }

    @Override
    public Timer timer(String name) {
        return timer(name, new Tag[0]);
    }

    @Override
    public Timer timer(String name, Tag... tags) {
        return timer(defaultMetadata(name), tags);
    }

    @Override
    public Timer timer(MetricID metricID) {
        Objects.requireNonNull(metricID, "metricID must not be null");
        return timer(defaultMetadata(metricID.getName()), metricID.getTagsAsArray());
    }

    @Override
    public Timer timer(Metadata metadata) {
        return timer(metadata, new Tag[0]);
    }

    @Override
    public Timer timer(Metadata metadata, Tag... tags) {
        var normalizedMetadata = requireMetadata(metadata);
        var metricId = validatedMetricID(normalizedMetadata.getName(), tags);
        metadataByName.putIfAbsent(metricId.getName(), normalizedMetadata);
        return registerOrGet(metricId, Timer.class, () -> new TimerImpl(metricId.getName()));
    }

    @Override
    public Metric getMetric(MetricID metricID) {
        return metrics.get(Objects.requireNonNull(metricID, "metricID must not be null"));
    }

    @Override
    public <T extends Metric> T getMetric(MetricID metricID, Class<T> metricClass) {
        var metric = getMetric(metricID);
        if (metric == null || !Objects.requireNonNull(metricClass, "metricClass must not be null").isInstance(metric)) {
            return null;
        }
        return metricClass.cast(metric);
    }

    @Override
    public Counter getCounter(MetricID metricID) {
        return getMetric(metricID, Counter.class);
    }

    @Override
    public Gauge<?> getGauge(MetricID metricID) {
        return getMetric(metricID, Gauge.class);
    }

    @Override
    public Histogram getHistogram(MetricID metricID) {
        return getMetric(metricID, Histogram.class);
    }

    @Override
    public Timer getTimer(MetricID metricID) {
        return getMetric(metricID, Timer.class);
    }

    @Override
    public Metadata getMetadata(String name) {
        return metadataByName.get(Objects.requireNonNull(name, "name must not be null"));
    }

    @Override
    public boolean remove(String name) {
        Objects.requireNonNull(name, "name must not be null");
        var removed = false;
        for (var metricID : new ArrayList<>(metrics.keySet())) {
            if (metricID.getName().equals(name)) {
                removed |= remove(metricID);
            }
        }
        return removed;
    }

    @Override
    public boolean remove(MetricID metricID) {
        Objects.requireNonNull(metricID, "metricID must not be null");
        var removed = metrics.remove(metricID) != null;
        if (removed && metrics.keySet().stream().noneMatch(existing -> existing.getName().equals(metricID.getName()))) {
            metadataByName.remove(metricID.getName());
            tagKeysByName.remove(metricID.getName());
        }
        return removed;
    }

    @Override
    public void removeMatching(MetricFilter filter) {
        var metricFilter = Objects.requireNonNull(filter, "filter must not be null");
        for (var entry : new ArrayList<>(metrics.entrySet())) {
            if (metricFilter.matches(entry.getKey(), entry.getValue())) {
                remove(entry.getKey());
            }
        }
    }

    @Override
    public SortedSet<String> getNames() {
        var names = new TreeSet<String>();
        metrics.keySet().forEach(metricID -> names.add(metricID.getName()));
        return Collections.unmodifiableSortedSet(names);
    }

    @Override
    public SortedSet<MetricID> getMetricIDs() {
        return Collections.unmodifiableSortedSet(new TreeSet<>(metrics.keySet()));
    }

    @Override
    public SortedMap<MetricID, Gauge> getGauges() {
        return getGauges(MetricFilter.ALL);
    }

    @Override
    public SortedMap<MetricID, Gauge> getGauges(MetricFilter filter) {
        return rawView(Gauge.class, filter);
    }

    @Override
    public SortedMap<MetricID, Counter> getCounters() {
        return getCounters(MetricFilter.ALL);
    }

    @Override
    public SortedMap<MetricID, Counter> getCounters(MetricFilter filter) {
        return typedView(Counter.class, filter);
    }

    @Override
    public SortedMap<MetricID, Histogram> getHistograms() {
        return getHistograms(MetricFilter.ALL);
    }

    @Override
    public SortedMap<MetricID, Histogram> getHistograms(MetricFilter filter) {
        return typedView(Histogram.class, filter);
    }

    @Override
    public SortedMap<MetricID, Timer> getTimers() {
        return getTimers(MetricFilter.ALL);
    }

    @Override
    public SortedMap<MetricID, Timer> getTimers(MetricFilter filter) {
        return typedView(Timer.class, filter);
    }

    @Override
    public SortedMap<MetricID, Metric> getMetrics(MetricFilter filter) {
        return typedView(Metric.class, filter);
    }

    @Override
    public <T extends Metric> SortedMap<MetricID, T> getMetrics(Class<T> metricClass, MetricFilter filter) {
        return typedView(metricClass, filter);
    }

    @Override
    public Map<MetricID, Metric> getMetrics() {
        return Collections.unmodifiableSortedMap(new TreeMap<>(metrics));
    }

    @Override
    public Map<String, Metadata> getMetadata() {
        return Collections.unmodifiableSortedMap(new TreeMap<>(metadataByName));
    }

    @Override
    public String getScope() {
        return scope;
    }

    public <T extends Metric> T register(MetricID metricID, T metric) {
        Objects.requireNonNull(metricID, "metricID must not be null");
        Objects.requireNonNull(metric, "metric must not be null");
        ensureTagKeySetConsistency(metricID.getName(), metricID.getTagsAsArray());
        metadataByName.putIfAbsent(metricID.getName(), defaultMetadata(metricID.getName()));
        return registerExisting(metricID, metric.getClass(), metric);
    }

    public <T extends Metric> T register(Metadata metadata, T metric, Tag... tags) {
        var normalizedMetadata = requireMetadata(metadata);
        Objects.requireNonNull(metric, "metric must not be null");
        ensureTagKeySetConsistency(normalizedMetadata.getName(), tags);
        metadataByName.putIfAbsent(normalizedMetadata.getName(), normalizedMetadata);
        return registerExisting(new MetricID(normalizedMetadata.getName(), normalizeTags(tags)), metric.getClass(), metric);
    }

    private <T extends Number> Gauge<T> registerGauge(MetricID metricID, Metadata metadata, Supplier<T> supplier) {
        Objects.requireNonNull(metricID, "metricID must not be null");
        Objects.requireNonNull(supplier, "supplier must not be null");
        metadataByName.putIfAbsent(metricID.getName(), metadata);
        return registerOrGet(metricID, Gauge.class, () -> new SupplierGauge<>(supplier));
    }

    private <T extends Metric> T registerExisting(MetricID metricID, Class<?> metricClass, T metric) {
        var existing = metrics.putIfAbsent(metricID, metric);
        if (existing == null) {
            return metric;
        }
        if (!metricClass.isInstance(existing)) {
            throw duplicateMetricType(metricID, metricClass, existing);
        }
        @SuppressWarnings("unchecked")
        var cast = (T) existing;
        return cast;
    }

    private <T extends Metric> T registerOrGet(MetricID metricID, Class<T> expectedType, Supplier<? extends T> factory) {
        var metric = metrics.compute(metricID, (ignored, existing) -> {
            if (existing == null) {
                return factory.get();
            }
            if (!expectedType.isInstance(existing)) {
                throw duplicateMetricType(metricID, expectedType, existing);
            }
            return existing;
        });
        return expectedType.cast(metric);
    }

    private <T extends Metric> SortedMap<MetricID, T> typedView(Class<T> metricClass, MetricFilter filter) {
        Objects.requireNonNull(metricClass, "metricClass must not be null");
        var metricFilter = Objects.requireNonNull(filter, "filter must not be null");
        var view = new TreeMap<MetricID, T>();
        metrics.forEach((metricID, metric) -> {
            if (metricClass.isInstance(metric) && metricFilter.matches(metricID, metric)) {
                view.put(metricID, metricClass.cast(metric));
            }
        });
        return Collections.unmodifiableSortedMap(view);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private SortedMap<MetricID, Gauge> rawView(Class<? extends Metric> metricClass, MetricFilter filter) {
        return (SortedMap) typedView((Class) metricClass, filter);
    }

    private static Tag[] normalizeTags(Tag[] tags) {
        if (tags == null || tags.length == 0) {
            return new Tag[0];
        }
        var byName = new TreeMap<String, String>();
        for (var tag : tags) {
            if (tag == null) {
                throw new IllegalArgumentException("tag must not be null");
            }
            var name = requireMetricName(tag.getTagName(), "tag name");
            if ("mp_scope".equals(name) || "mp_app".equals(name)) {
                throw new IllegalArgumentException("tag name '" + name + "' is reserved");
            }
            byName.put(name, Objects.requireNonNull(tag.getTagValue(), "tag value must not be null"));
        }
        return byName.entrySet().stream()
                .map(entry -> new Tag(entry.getKey(), entry.getValue()))
                .toArray(Tag[]::new);
    }

    private MetricID validatedMetricID(String name, Tag[] tags) {
        var normalizedTags = normalizeTags(tags);
        ensureTagKeySetConsistency(name, normalizedTags);
        return new MetricID(name, normalizedTags);
    }

    private void ensureTagKeySetConsistency(String metricName, Tag[] tags) {
        var normalizedName = requireMetricName(metricName, "metric name");
        var keySet = new TreeSet<String>();
        for (var tag : normalizeTags(tags)) {
            keySet.add(tag.getTagName());
        }
        tagKeysByName.compute(normalizedName, (ignored, existing) -> {
            if (existing == null) {
                return Collections.unmodifiableSortedSet(new TreeSet<>(keySet));
            }
            if (!existing.equals(keySet)) {
                throw new IllegalArgumentException("Metric '" + normalizedName + "' already exists with different tag keys");
            }
            return existing;
        });
    }

    private static Metadata defaultMetadata(String name) {
        return Metadata.builder()
                .withName(requireMetricName(name, "metric name"))
                .withUnit(MetricUnits.NONE)
                .build();
    }

    private static Metadata requireMetadata(Metadata metadata) {
        var result = Objects.requireNonNull(metadata, "metadata must not be null");
        requireMetricName(result.getName(), "metadata name");
        return result;
    }

    private static String requireMetricName(String value, String label) {
        var normalized = Objects.requireNonNull(value, label + " must not be null").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return normalized;
    }

    private static <T, R extends Number> Function<T, R> requireGaugeFunction(Function<T, R> function) {
        return Objects.requireNonNull(function, "function must not be null");
    }

    private static DiracException duplicateMetricType(MetricID metricID, Class<?> expectedType, Metric existing) {
        return new DiracException("Metric '" + metricID + "' is already registered as "
                + existing.getClass().getSimpleName() + " and cannot be reused as " + expectedType.getSimpleName());
    }

    private static UnsupportedOperationException unsupportedMetricType(String type, String name) {
        return new UnsupportedOperationException(type + " support will be introduced in a later milestone for metric '" + name + "'");
    }

    private record SupplierGauge<T extends Number>(Supplier<T> supplier) implements Gauge<T> {
        @Override
        public T getValue() {
            return supplier.get();
        }
    }
}
