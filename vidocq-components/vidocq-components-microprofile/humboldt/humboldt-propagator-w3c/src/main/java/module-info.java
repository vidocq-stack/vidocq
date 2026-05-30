/**
 * Humboldt W3C Propagator — composite facade assembling
 * {@code W3CTraceContextPropagator} and {@code W3CBaggagePropagator} from the
 * OpenTelemetry public API into a {@code ContextPropagators}.
 *
 * <p>No reimplementation: both propagators are concrete classes in
 * {@code opentelemetry-api}. This module only provides the canonical
 * composition used by Humboldt (see PLAN.md §13 milestone M3).</p>
 */
module io.vidocq.humboldt.propagator.w3c {

    requires transitive io.opentelemetry.api;
    requires transitive io.opentelemetry.context;

    exports io.vidocq.humboldt.propagator.w3c;
}
