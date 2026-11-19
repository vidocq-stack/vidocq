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
package io.vidocq.dirac.tck.arquillian;

import io.vidocq.dirac.cdi.internal.MetricRegistryProducerBean;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.Gauge;
import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.Tag;
import org.eclipse.microprofile.metrics.Timer;
import org.eclipse.microprofile.metrics.annotation.Metric;
import org.eclipse.microprofile.metrics.annotation.RegistryScope;
import org.eclipse.microprofile.metrics.annotation.RegistryType;
import org.jboss.arquillian.test.spi.TestEnricher;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Arquillian {@link TestEnricher} — injects {@code @Inject} fields of each TCK test instance
 * using the Vauban CDI container started by {@link VaubanDiracTckBootstrap}.
 *
 * <p>Special cases:</p>
 * <ul>
 *   <li>{@link MetricRegistry} + {@link RegistryScope} : {@code @Nonbinding} on {@code scope} —
 *       reads the value directly and delegates to {@link MetricRegistryProducerBean#registry}.</li>
 *   <li>{@link Counter}/{@link Timer}/{@link Histogram}/{@link Gauge} : CDI producer requires
 *       {@code InjectionPoint} unavailable outside container — resolves directly via registry.</li>
 *   <li>Test method parameters: resolved by type + {@link Metric} annotation.</li>
 * </ul>
 */
public class DiracTestEnricher implements TestEnricher {

    @Override
    public void enrich(Object testInstance) {
        VaubanContainer container = VaubanContainer.current();
        if (container == null) return;
        resetRequestContext(container);
        BeanManager bm = container.getBeanManager();
        for (Field field : getAllFields(testInstance.getClass())) {
            if (!field.isAnnotationPresent(Inject.class)) continue;
            try { injectField(testInstance, field, bm); } catch (Exception ignored) {}
        }
    }

    @Override
    public Object[] resolve(Method method) {
        VaubanContainer container = VaubanContainer.current();
        if (container != null) {
            resetRequestContext(container);
        }

        Parameter[] params = method.getParameters();
        if (params.length == 0) return new Object[0];

        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            args[i] = resolveParameter(params[i], method, container);
        }
        return args;
    }

    private static Object resolveParameter(Parameter param, Method method, VaubanContainer container) {
        if (container == null) return null;
        Class<?> type = param.getType();
        BeanManager bm = container.getBeanManager();

        if (MetricRegistry.class.isAssignableFrom(type)) {
            RegistryScope rs = param.getAnnotation(RegistryScope.class);
            String scope = (rs != null && !rs.scope().isBlank()) ? rs.scope() : MetricRegistry.APPLICATION_SCOPE;
            return resolveRegistryByScope(bm, scope);
        }

        Metric ann = param.getAnnotation(Metric.class);
        if (Counter.class.isAssignableFrom(type) || Timer.class.isAssignableFrom(type)
                || Histogram.class.isAssignableFrom(type)) {
            return resolveMetricFromRegistry(bm, type, method.getDeclaringClass(), param.getName(), ann);
        }
        if (Gauge.class.isAssignableFrom(type)) {
            return resolveGaugeFromRegistry(bm, method.getDeclaringClass(), param.getName(), ann);
        }

        return null;
    }

    private static void resetRequestContext(VaubanContainer container) {
        var requestContext = container.requestContext();
        if (requestContext.isActive()) {
            requestContext.deactivate();
        }
        requestContext.activate();
    }

    private static void injectField(Object testInstance, Field field, BeanManager bm) throws Exception {
        var type = field.getType();

        // MetricRegistry injection — @RegistryScope.scope is @Nonbinding, bypass CDI
        if (MetricRegistry.class.isAssignableFrom(type) && field.getAnnotation(RegistryType.class) == null) {
            RegistryScope rs = field.getAnnotation(RegistryScope.class);
            String scope = (rs != null && !rs.scope().isBlank())
                    ? rs.scope()
                    : MetricRegistry.APPLICATION_SCOPE;
            MetricRegistry registry = resolveRegistryByScope(bm, scope);
            if (registry != null) {
                field.setAccessible(true);
                field.set(testInstance, registry);
            }
            return;
        }

        // Counter / Timer / Histogram injection — producer requires InjectionPoint, bypass CDI
        Metric ann = field.getAnnotation(Metric.class);
        if (Counter.class.isAssignableFrom(type) || Timer.class.isAssignableFrom(type)
                || Histogram.class.isAssignableFrom(type)) {
            Object metric = resolveMetricFromRegistry(bm, type, field.getDeclaringClass(), field.getName(), ann);
            if (metric != null) {
                field.setAccessible(true);
                field.set(testInstance, metric);
            }
            return;
        }

        // Gauge injection — look up by MetricID in the registry
        if (Gauge.class.isAssignableFrom(type)) {
            Object gauge = resolveGaugeFromRegistry(bm, field.getDeclaringClass(), field.getName(), ann);
            if (gauge != null) {
                field.setAccessible(true);
                field.set(testInstance, gauge);
            }
            return;
        }

        // Instance<T> injection
        if (Instance.class.equals(type)) {
            Class<?> targetType = extractInstanceTargetType(field.getGenericType());
            if (targetType != null) {
                var qualifiers = extractQualifiers(field);
                Object selected = bm.createInstance().select(targetType, qualifiers.toArray(new Annotation[0]));
                field.setAccessible(true);
                field.set(testInstance, selected);
                return;
            }
        }

        // Standard CDI injection
        var qualifiers = extractQualifiers(field);
        var beans = bm.getBeans(type, qualifiers.toArray(new Annotation[0]));
        if (beans == null || beans.isEmpty()) return;
        var resolved = bm.resolve(beans);
        if (resolved == null) return;
        var ctx = bm.createCreationalContext(resolved);
        Object instance = bm.getReference(resolved, type, ctx);
        if (instance == null) return;
        field.setAccessible(true);
        field.set(testInstance, instance);
    }

    private static Object resolveMetricFromRegistry(BeanManager bm, Class<?> type,
                                                     Class<?> declaringClass, String memberName, Metric ann) {
        try {
            MetricRegistryProducerBean producer = getProducerBean(bm);
            if (producer == null) return null;
            String name = resolveMetricName(declaringClass, memberName, ann);
            Tag[] tags = resolveTags(ann);
            String scope = resolveScope(ann);
            MetricRegistry registry = producer.registry(scope);
            if (Counter.class.isAssignableFrom(type)) return registry.counter(name, tags);
            if (Timer.class.isAssignableFrom(type)) return registry.timer(name, tags);
            if (Histogram.class.isAssignableFrom(type)) return registry.histogram(name, tags);
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Object resolveGaugeFromRegistry(BeanManager bm, Class<?> declaringClass,
                                                    String memberName, Metric ann) {
        try {
            MetricRegistryProducerBean producer = getProducerBean(bm);
            if (producer == null) return null;
            String name = resolveMetricName(declaringClass, memberName, ann);
            Tag[] tags = resolveTags(ann);
            String scope = resolveScope(ann);
            MetricRegistry registry = producer.registry(scope);
            return registry.getGauge(new MetricID(name, tags));
        } catch (Exception e) {
            return null;
        }
    }

    private static MetricRegistryProducerBean getProducerBean(BeanManager bm) {
        try {
            var beans = bm.getBeans(MetricRegistryProducerBean.class);
            if (beans == null || beans.isEmpty()) return null;
            var bean = bm.resolve(beans);
            if (bean == null) return null;
            var ctx = bm.createCreationalContext(bean);
            return (MetricRegistryProducerBean) bm.getReference(bean, MetricRegistryProducerBean.class, ctx);
        } catch (Exception e) {
            return null;
        }
    }

    private static MetricRegistry resolveRegistryByScope(BeanManager bm, String scope) {
        try {
            MetricRegistryProducerBean producer = getProducerBean(bm);
            return producer == null ? null : producer.registry(scope);
        } catch (Exception e) {
            return null;
        }
    }

    private static String resolveMetricName(Class<?> declaringClass, String memberName, Metric ann) {
        if (ann != null && !ann.name().isBlank()) {
            return ann.absolute() ? ann.name() : MetricRegistry.name(declaringClass, ann.name());
        }
        if (ann != null && ann.absolute()) return memberName;
        return MetricRegistry.name(declaringClass, memberName);
    }

    private static Tag[] resolveTags(Metric ann) {
        if (ann == null || ann.tags().length == 0) return new Tag[0];
        var result = new ArrayList<Tag>();
        for (String t : ann.tags()) {
            if (t == null || t.isBlank()) continue;
            int sep = t.indexOf('=');
            if (sep <= 0 || sep == t.length() - 1) continue;
            try {
                result.add(new Tag(t.substring(0, sep).trim(), t.substring(sep + 1).trim()));
            } catch (IllegalArgumentException ignored) {}
        }
        return result.toArray(new Tag[0]);
    }

    private static String resolveScope(Metric ann) {
        if (ann == null) return MetricRegistry.APPLICATION_SCOPE;
        String s = ann.scope();
        return (s == null || s.isBlank()) ? MetricRegistry.APPLICATION_SCOPE : s;
    }

    private static Class<?> extractInstanceTargetType(Type genericType) {
        if (!(genericType instanceof ParameterizedType pt)) return null;
        if (pt.getActualTypeArguments().length != 1) return null;
        Type arg = pt.getActualTypeArguments()[0];
        return (arg instanceof Class<?> c) ? c : null;
    }

    private static List<Annotation> extractQualifiers(Field field) {
        var qualifiers = new ArrayList<Annotation>();
        for (Annotation ann : field.getAnnotations()) {
            if (ann.annotationType().equals(Inject.class)) continue;
            qualifiers.add(ann);
        }
        return qualifiers;
    }

    private static List<Field> getAllFields(Class<?> clazz) {
        var fields = new ArrayList<Field>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) fields.add(f);
            current = current.getSuperclass();
        }
        return fields;
    }
}
