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
 * {@link TestEnricher} Arquillian Humboldt — injecte les champs {@code @Inject}
 * de la classe de test via le {@link VaubanContainer} courant.
 *
 * <p>Sans cet enricher, les TCK qui font {@code @Inject OpenTelemetry / Tracer /
 * InMemorySpanExporter} resteraient {@code null} après le {@code deploy()}.</p>
 *
 * <p>Cas particulier : {@link OpenTelemetry} — résolu via
 * {@link GlobalOpenTelemetry#get()} car c'est le seul moyen propre d'exposer
 * l'instance configurée par le container (Humboldt n'enregistre pas
 * d'OpenTelemetry comme bean CDI managed).</p>
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
                    LOG.log(Level.WARNING, "  ⚠ @Inject non résolu : {0}.{1} (type={2})",
                            cls.getSimpleName(), f.getName(), f.getType().getName());
                    continue;
                }
                try {
                    f.setAccessible(true);
                    f.set(testCase, value);
                    LOG.log(Level.DEBUG, "  → @Inject résolu : {0}.{1} = {2}",
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
        // M7b.4b.4 ne résout pas les arguments de méthodes — TestNG @Test ne
        // les utilise pas pour les TCK Telemetry. À ajouter si une suite TCK
        // future en a besoin.
        return new Object[method.getParameterCount()];
    }

    private static Object resolveValue(Field f, VaubanContainer container) {
        Class<?> type = f.getType();
        // Types OpenTelemetry résolus directement (cas où Vauban ne sait pas
        // appeler les producers — limites CDI Lite + classpath isolation).
        if (OpenTelemetry.class.equals(type)) {
            return GlobalOpenTelemetry.get();
        }
        if (Tracer.class.equals(type)) {
            return GlobalOpenTelemetry.get().getTracer(f.getDeclaringClass().getName());
        }
        if (Meter.class.equals(type)) {
            return GlobalOpenTelemetry.get().getMeter(f.getDeclaringClass().getName());
        }
        if (Span.class.equals(type)) {
            return Span.current();
        }
        if (Baggage.class.equals(type)) {
            return Baggage.current();
        }
        if (io.opentelemetry.api.logs.Logger.class.equals(type)) {
            return GlobalOpenTelemetry.get().getLogsBridge().get(f.getDeclaringClass().getName());
        }
        if (container == null) return null;
        try {
            return container.select(type);
        } catch (RuntimeException e) {
            // bean non résolu : on laisse l'enricher remonter null (warning loggé)
            return null;
        }
    }
}
