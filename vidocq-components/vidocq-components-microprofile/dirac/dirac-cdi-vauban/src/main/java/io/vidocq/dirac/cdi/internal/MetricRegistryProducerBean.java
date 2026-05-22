package io.vidocq.dirac.cdi.internal;

import io.vidocq.dirac.internal.BaseMetricsRegistrar;
import io.vidocq.dirac.internal.MetricRegistryImpl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.InjectionPoint;
import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.Tag;
import org.eclipse.microprofile.metrics.Timer;
import org.eclipse.microprofile.metrics.annotation.Metric;
import org.eclipse.microprofile.metrics.annotation.RegistryScope;
import org.eclipse.microprofile.metrics.annotation.RegistryType;

import java.lang.reflect.Member;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Producers CDI des registres MicroProfile Metrics et des instances de métriques injectables.
 *
 * <p>Un seul producer {@code @RegistryScope} (via {@link InjectionPoint}) suffit car
 * {@code @RegistryScope.scope} est {@code @Nonbinding} — CDI ignore la valeur de scope
 * pour la résolution des beans. Le producer lit la valeur réelle à l'exécution.</p>
 */
@ApplicationScoped
public class MetricRegistryProducerBean {
    private final ConcurrentHashMap<String, MetricRegistry> registryMap = new ConcurrentHashMap<>();

    public MetricRegistryProducerBean() {
        registryMap.put(MetricRegistry.APPLICATION_SCOPE, new MetricRegistryImpl(MetricRegistry.APPLICATION_SCOPE));
        registryMap.put(MetricRegistry.BASE_SCOPE, new MetricRegistryImpl(MetricRegistry.BASE_SCOPE));
        registryMap.put(MetricRegistry.VENDOR_SCOPE, new MetricRegistryImpl(MetricRegistry.VENDOR_SCOPE));
        new BaseMetricsRegistrar().register(registryMap.get(MetricRegistry.BASE_SCOPE));
    }

    // ------------------------------------------------------------------
    // Producers pour MetricRegistry (par scope)
    // ------------------------------------------------------------------

    /**
     * Producer unique pour {@code @RegistryScope} — résout le scope via l'InjectionPoint
     * car {@code @RegistryScope.scope} est {@code @Nonbinding}.
     * Crée un nouveau registre pour tout scope inconnu (ex : "customScope").
     */
    @Produces
    @RegistryScope
    public MetricRegistry produceByScope(InjectionPoint ip) {
        String scope = MetricRegistry.APPLICATION_SCOPE;
        if (ip != null && ip.getAnnotated() != null) {
            RegistryScope ann = ip.getAnnotated().getAnnotation(RegistryScope.class);
            if (ann != null && !ann.scope().isBlank()) {
                scope = ann.scope();
            }
        }
        return registryMap.computeIfAbsent(scope, MetricRegistryImpl::new);
    }

    // Deprecated @RegistryType support (MP Metrics < 5.0 compat)
    @Produces
    @RegistryType
    public MetricRegistry produceApplicationByType() {
        return registryMap.get(MetricRegistry.APPLICATION_SCOPE);
    }

    @Produces
    @RegistryType(type = MetricRegistry.Type.BASE)
    public MetricRegistry produceBaseByType() {
        return registryMap.get(MetricRegistry.BASE_SCOPE);
    }

    @Produces
    @RegistryType(type = MetricRegistry.Type.VENDOR)
    public MetricRegistry produceVendorByType() {
        return registryMap.get(MetricRegistry.VENDOR_SCOPE);
    }

    // ------------------------------------------------------------------
    // Producers pour injection directe de métriques via @Inject
    // ------------------------------------------------------------------

    @Produces
    @Dependent
    public Counter produceCounter(InjectionPoint ip) {
        Metric ann = ip.getAnnotated().getAnnotation(Metric.class);
        String name = resolveMetricName(ip.getMember(), ann);
        Tag[] tags = resolveTags(ann);
        return registry(resolveScope(ann)).counter(name, tags);
    }

    @Produces
    @Dependent
    public Timer produceTimer(InjectionPoint ip) {
        Metric ann = ip.getAnnotated().getAnnotation(Metric.class);
        String name = resolveMetricName(ip.getMember(), ann);
        Tag[] tags = resolveTags(ann);
        return registry(resolveScope(ann)).timer(name, tags);
    }

    @Produces
    @Dependent
    public Histogram produceHistogram(InjectionPoint ip) {
        Metric ann = ip.getAnnotated().getAnnotation(Metric.class);
        String name = resolveMetricName(ip.getMember(), ann);
        Tag[] tags = resolveTags(ann);
        return registry(resolveScope(ann)).histogram(name, tags);
    }

    // ------------------------------------------------------------------
    // Accès interne au registre par scope
    // ------------------------------------------------------------------

    public MetricRegistry registry(String scope) {
        return registryMap.computeIfAbsent(scope, MetricRegistryImpl::new);
    }

    MetricRegistry applicationRegistry() {
        return registryMap.get(MetricRegistry.APPLICATION_SCOPE);
    }

    MetricRegistry baseRegistry() {
        return registryMap.get(MetricRegistry.BASE_SCOPE);
    }

    MetricRegistry vendorRegistry() {
        return registryMap.get(MetricRegistry.VENDOR_SCOPE);
    }

    // ------------------------------------------------------------------
    // Helpers de résolution de noms / tags / scope
    // ------------------------------------------------------------------

    private static String resolveMetricName(Member member, Metric ann) {
        if (ann != null && !ann.name().isBlank()) {
            return ann.absolute() ? ann.name() : MetricRegistry.name(member.getDeclaringClass(), ann.name());
        }
        if (ann != null && ann.absolute()) return member.getName();
        return MetricRegistry.name(member.getDeclaringClass(), member.getName());
    }

    private static String resolveScope(Metric ann) {
        if (ann == null) return MetricRegistry.APPLICATION_SCOPE;
        String s = ann.scope();
        return (s == null || s.isBlank()) ? MetricRegistry.APPLICATION_SCOPE : s;
    }

    private static Tag[] resolveTags(Metric ann) {
        if (ann == null || ann.tags().length == 0) return new Tag[0];
        return Arrays.stream(ann.tags())
                .filter(t -> t != null && !t.isBlank())
                .map(t -> {
                    int sep = t.indexOf('=');
                    if (sep <= 0 || sep == t.length() - 1) return null;
                    return new Tag(t.substring(0, sep).trim(), t.substring(sep + 1).trim());
                })
                .filter(t -> t != null)
                .toArray(Tag[]::new);
    }
}
