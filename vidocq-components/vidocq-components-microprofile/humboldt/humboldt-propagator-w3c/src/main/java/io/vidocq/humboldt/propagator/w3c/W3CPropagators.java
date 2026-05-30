package io.vidocq.humboldt.propagator.w3c;

import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;

/**
 * Canonical W3C TraceContext + Baggage composite used by Humboldt.
 *
 * <p>Equivalent to:</p>
 * <pre>{@code
 * ContextPropagators.create(
 *     TextMapPropagator.composite(
 *         W3CTraceContextPropagator.getInstance(),
 *         W3CBaggagePropagator.getInstance()));
 * }</pre>
 *
 * <p>Spec: <a href="https://www.w3.org/TR/trace-context/">W3C TraceContext</a>
 * and <a href="https://www.w3.org/TR/baggage/">W3C Baggage</a>.</p>
 */
public final class W3CPropagators {

    private W3CPropagators() {}

    /**
     * @return the composite W3C propagators (traceparent + tracestate + baggage).
     */
    public static ContextPropagators get() {
        return Holder.INSTANCE;
    }

    /**
     * @return the underlying composite {@link TextMapPropagator}, useful for
     *         registering it in another {@code ContextPropagators}.
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
