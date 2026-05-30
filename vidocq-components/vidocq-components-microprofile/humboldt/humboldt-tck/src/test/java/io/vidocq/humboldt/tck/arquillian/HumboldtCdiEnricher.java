package io.vidocq.humboldt.tck.arquillian;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.inject.Inject;
import org.jboss.arquillian.test.spi.TestEnricher;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Humboldt Arquillian {@link TestEnricher} — injects {@code @Inject} fields
 * on the test class through the current {@link VaubanContainer}.
 *
 * <p>Without this enricher, TCK tests using {@code @Inject OpenTelemetry / Tracer /
 * InMemorySpanExporter} would remain {@code null} after {@code deploy()}.</p>
 *
 * <p>Special case: {@link OpenTelemetry} — resolved through
 * {@link GlobalOpenTelemetry#get()} because that is the only clean way to expose
 * the instance configured by the container (Humboldt does not register
 * OpenTelemetry as a managed CDI bean).</p>
 */
public class HumboldtCdiEnricher implements TestEnricher {

    private static final Logger LOG = System.getLogger(HumboldtCdiEnricher.class.getName());

    @Override
    public void enrich(Object testCase) {
        VaubanContainer container = VaubanContainer.current();
        Class<?> cls = testCase.getClass();

        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (!f.isAnnotationPresent(Inject.class)) continue;
                Object value = resolveValue(f, container);
                if (value == null) {
                    LOG.log(Level.WARNING, "  ⚠ @Inject unresolved: {0}.{1} (type={2})",
                            cls.getSimpleName(), f.getName(), f.getType().getName());
                    continue;
                }
                try {
                    f.setAccessible(true);
                    f.set(testCase, value);
                    LOG.log(Level.DEBUG, "  -> @Inject resolved: {0}.{1} = {2}",
                            cls.getSimpleName(), f.getName(), value.getClass().getSimpleName());
                } catch (IllegalAccessException e) {
                    LOG.log(Level.WARNING, "  ⚠ set field failed : {0}.{1} — {2}",
                            cls.getSimpleName(), f.getName(), e.getMessage());
                }
            }
            cls = cls.getSuperclass();
        }
    }

    @Override
    public Object[] resolve(Method method) {
        // M7b.4b.4 does not resolve method arguments — TestNG @Test does not
        // use them for the Telemetry TCKs. Add this if a future TCK suite
        // needs it.
        return new Object[method.getParameterCount()];
    }

    private static Object resolveValue(Field f, VaubanContainer container) {
        Class<?> type = f.getType();
        // OpenTelemetry types resolved directly (for cases where Vauban cannot
        // call producers — CDI Lite limitations + classpath isolation).
        if (OpenTelemetry.class.equals(type)) {
            return GlobalOpenTelemetry.get();
        }
        if (Tracer.class.equals(type)) {
            return GlobalOpenTelemetry.get().getTracer(f.getDeclaringClass().getName());
        }
        if (Meter.class.equals(type)) {
            return GlobalOpenTelemetry.get().getMeter(f.getDeclaringClass().getName());
        }
        // Span / Baggage: dynamic proxy delegating to .current() on each call.
        // Capturing .current() here would freeze the value at enrichment time, while
        // the SpanBeanTest/BaggageBeanTest TCKs mutate the Context after injection and
        // expect injectedSpan/injectedBaggage to reflect the current value.
        if (Span.class.equals(type)) {
            return java.lang.reflect.Proxy.newProxyInstance(
                    Span.class.getClassLoader(), new Class<?>[]{Span.class},
                    (p, m, a) -> m.invoke(Span.current(), a));
        }
        if (Baggage.class.equals(type)) {
            return java.lang.reflect.Proxy.newProxyInstance(
                    Baggage.class.getClassLoader(), new Class<?>[]{Baggage.class},
                    (p, m, a) -> m.invoke(Baggage.current(), a));
        }
        if (io.opentelemetry.api.logs.Logger.class.equals(type)) {
            return GlobalOpenTelemetry.get().getLogsBridge().get(f.getDeclaringClass().getName());
        }
        if (container == null) return null;
        try {
            return container.select(type);
        } catch (RuntimeException e) {
            // unresolved bean: let the enricher surface null (warning logged)
            return null;
        }
    }
}
