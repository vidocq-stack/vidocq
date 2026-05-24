package io.vidocq.humboldt.cdi;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.InjectionPoint;

import java.lang.reflect.Proxy;

/**
 * Producers CDI pour les types OpenTelemetry standard exigés par la spec
 * MicroProfile Telemetry 2.1 §"Required CDI beans".
 *
 * <ul>
 *   <li>{@link OpenTelemetry} — résolu via {@link GlobalOpenTelemetry#get()}</li>
 *   <li>{@link Tracer} — résolu via le scope name dérivé du point d'injection</li>
 *   <li>{@link Span} — {@link Span#current()} au moment de la résolution</li>
 *   <li>{@link Baggage} — {@link Baggage#current()} au moment de la résolution</li>
 * </ul>
 *
 * <p>Permet aux applications d'écrire simplement {@code @Inject Tracer tracer}
 * ou {@code @Inject Span current} sans déclarer leurs propres producers.</p>
 */
@ApplicationScoped
public class HumboldtTelemetryProducers {

    /**
     * Producer {@link OpenTelemetry} — l'instance globale configurée par
     * {@link io.vidocq.humboldt.runtime.HumboldtAutoConfigure} (ou
     * {@link GlobalOpenTelemetry#set(OpenTelemetry)} si l'app n'utilise pas
     * l'autoconfig).
     */
    @Produces
    public OpenTelemetry produceOpenTelemetry() {
        return GlobalOpenTelemetry.get();
    }

    /**
     * Producer {@link Tracer} — le nom du tracer est dérivé du point d'injection :
     * classe déclarante par défaut. Convention OTel : {@code getTracer(scope)}.
     */
    @Produces
    public Tracer produceTracer(InjectionPoint ip) {
        String scope = ip != null && ip.getMember() != null
                ? ip.getMember().getDeclaringClass().getName()
                : "io.vidocq.humboldt.cdi";
        return GlobalOpenTelemetry.get().getTracer(scope);
    }

    /**
     * Producer {@link Span} — retourne un proxy dynamique qui délègue chaque
     * appel de méthode à {@link Span#current()} au moment de l'invocation
     * (pas au moment de l'injection). Spec MP Telemetry 2.1 §"Required CDI
     * beans" : {@code SpanBeanTest.spanBeanChange} mute le Context après
     * l'injection et attend que les accès subséquents à {@code injectedSpan}
     * reflètent le nouveau span courant.
     */
    @Produces
    public Span produceCurrentSpan() {
        return (Span) Proxy.newProxyInstance(
                Span.class.getClassLoader(),
                new Class<?>[]{Span.class},
                (proxy, method, args) -> method.invoke(Span.current(), args));
    }

    /**
     * Producer {@link Baggage} — proxy dynamique qui delegate à
     * {@link Baggage#current()} à chaque appel. Pour
     * {@code BaggageBeanTest.baggageBeanChange} qui mute le Context après
     * l'injection (cf. {@link #produceCurrentSpan()} pour la même approche).
     */
    @Produces
    public Baggage produceCurrentBaggage() {
        return (Baggage) Proxy.newProxyInstance(
                Baggage.class.getClassLoader(),
                new Class<?>[]{Baggage.class},
                (proxy, method, args) -> method.invoke(Baggage.current(), args));
    }

    /**
     * Producer {@link Meter} — le nom du meter est dérivé du point d'injection :
     * classe déclarante par défaut. Convention OTel : {@code getMeter(scope)}.
     */
    @Produces
    public Meter produceMeter(InjectionPoint ip) {
        String scope = ip != null && ip.getMember() != null
                ? ip.getMember().getDeclaringClass().getName()
                : "io.vidocq.humboldt.cdi";
        return GlobalOpenTelemetry.get().getMeter(scope);
    }

    /**
     * Producer {@link Logger} (logs OTel) — le nom du logger est dérivé du
     * point d'injection.
     */
    @Produces
    public Logger produceLogger(InjectionPoint ip) {
        String scope = ip != null && ip.getMember() != null
                ? ip.getMember().getDeclaringClass().getName()
                : "io.vidocq.humboldt.cdi";
        return GlobalOpenTelemetry.get().getLogsBridge().get(scope);
    }
}
