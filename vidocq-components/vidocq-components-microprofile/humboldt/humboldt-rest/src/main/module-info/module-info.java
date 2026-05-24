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
    requires jakarta.annotation;  // @Priority sur HumboldtSpanFinalizer + Client*Filter
    requires java.logging;
    // M7c.12 — MicroProfile Rest Client : optionnel. Le HumboldtMpRestClientListener
    // n'est invoqué que si MP Rest Client est en runtime classpath (typiquement
    // via cyrano-core). `requires static` = compile-time only, pas une dep runtime.
    requires static microprofile.rest.client.api;

    exports io.vidocq.humboldt.rest;

    // M7c.7 — Auto-discovery par CassiniClientBuilder (et tout JAX-RS Client compatible)
    // qui scanne ServiceLoader<Feature> au build() — instrumente automatiquement les
    // requêtes sortantes avec un span kind=CLIENT (conforme MP Telemetry §3.2).
    provides jakarta.ws.rs.core.Feature
            with io.vidocq.humboldt.rest.HumboldtClientTracingFeature;

    // M7c.12 — Auto-instrumentation des Rest Client MP via le SPI standard
    // RestClientListener.onNewClient() (spec MP Rest Client 4.0 §10.2). Implémenté
    // par Cyrano dans CyranoRestClientBuilder.build().
    provides org.eclipse.microprofile.rest.client.spi.RestClientListener
            with io.vidocq.humboldt.rest.HumboldtMpRestClientListener;
}
