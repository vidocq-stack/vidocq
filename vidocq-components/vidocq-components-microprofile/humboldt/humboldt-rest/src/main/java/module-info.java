/**
 * Humboldt REST — filters JAX-RS pour instrumenter automatiquement les endpoints
 * HTTP côté serveur (tracing OpenTelemetry).
 *
 * <p>M6b MVP : {@link io.vidocq.humboldt.rest.HumboldtServerRequestFilter} et
 * {@link io.vidocq.humboldt.rest.HumboldtServerResponseFilter} couplés via
 * {@code ContainerRequestContext.setProperty()}. Conventions OTel HTTP
 * semantic conventions 1.27+.</p>
 */
module io.vidocq.humboldt.rest {

    requires transitive io.vidocq.humboldt.api;
    requires transitive io.vidocq.humboldt.sdk.trace;
    requires transitive io.vidocq.humboldt.propagator.w3c;
    requires transitive io.opentelemetry.api;
    requires transitive io.opentelemetry.context;
    requires transitive jakarta.ws.rs;
    requires transitive jakarta.cdi;  // @ApplicationScoped sur les filters @Provider
    requires java.logging;

    exports io.vidocq.humboldt.rest;
}
