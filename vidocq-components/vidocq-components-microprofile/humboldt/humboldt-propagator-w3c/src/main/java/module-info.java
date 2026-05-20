/**
 * Humboldt Propagator W3C — façade composite assemblant
 * {@code W3CTraceContextPropagator} et {@code W3CBaggagePropagator} de l'API
 * publique OpenTelemetry en un {@code ContextPropagators}.
 *
 * <p>Aucune réimplémentation : les deux propagators sont des classes concrètes
 * dans {@code opentelemetry-api}. Ce module fournit uniquement la composition
 * canonique utilisée par Humboldt (cf. PLAN.md §13 jalon M3).</p>
 */
module io.vidocq.humboldt.propagator.w3c {

    requires transitive io.opentelemetry.api;
    requires transitive io.opentelemetry.context;

    exports io.vidocq.humboldt.propagator.w3c;
}
