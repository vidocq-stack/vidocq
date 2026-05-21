package io.vidocq.humboldt.cdi;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.InjectionPoint;

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
     * Producer {@link Span} — capture {@link Span#current()} au moment de
     * la résolution. Pour les TCK MP Telemetry qui font
     * {@code @Inject Span injectedSpan;} puis comparent à {@link Span#current()}
     * dans la même méthode test.
     */
    @Produces
    public Span produceCurrentSpan() {
        return Span.current();
    }

    /**
     * Producer {@link Baggage} — capture {@link Baggage#current()} au moment
     * de la résolution.
     */
    @Produces
    public Baggage produceCurrentBaggage() {
        return Baggage.current();
    }
}
