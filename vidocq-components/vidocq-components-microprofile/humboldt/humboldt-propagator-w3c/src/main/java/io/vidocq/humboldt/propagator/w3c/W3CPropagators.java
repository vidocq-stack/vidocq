package io.vidocq.humboldt.propagator.w3c;

import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;

/**
 * Composite canonique W3C TraceContext + Baggage utilisé par Humboldt.
 *
 * <p>Équivalent à :</p>
 * <pre>{@code
 * ContextPropagators.create(
 *     TextMapPropagator.composite(
 *         W3CTraceContextPropagator.getInstance(),
 *         W3CBaggagePropagator.getInstance()));
 * }</pre>
 *
 * <p>Spec : <a href="https://www.w3.org/TR/trace-context/">W3C TraceContext</a>
 * et <a href="https://www.w3.org/TR/baggage/">W3C Baggage</a>.</p>
 */
public final class W3CPropagators {

    private W3CPropagators() {}

    /**
     * @return les propagators W3C composites (traceparent + tracestate + baggage).
     */
    public static ContextPropagators get() {
        return Holder.INSTANCE;
    }

    /**
     * @return le {@link TextMapPropagator} composite sous-jacent, utile pour
     *         l'enregistrer dans un autre {@code ContextPropagators}.
     */
    public static TextMapPropagator textMap() {
        return Holder.TEXT_MAP;
    }

    private static final class Holder {
        static final TextMapPropagator TEXT_MAP = TextMapPropagator.composite(
                W3CTraceContextPropagator.getInstance(),
                W3CBaggagePropagator.getInstance());
        static final ContextPropagators INSTANCE = ContextPropagators.create(TEXT_MAP);
    }
}
