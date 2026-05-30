/**
 * Humboldt REST — JAX-RS filters for automatic HTTP endpoint instrumentation
 * on the server side (OpenTelemetry tracing).
 *
 * <p>M6b MVP: {@link io.vidocq.humboldt.rest.HumboldtServerRequestFilter} and
 * {@link io.vidocq.humboldt.rest.HumboldtServerResponseFilter} coupled via
 * {@code ContainerRequestContext.setProperty()}. Follows OTel HTTP
 * semantic conventions 1.27+.</p>
 */
module io.vidocq.humboldt.rest {

    requires transitive io.vidocq.humboldt.api;
    requires transitive io.vidocq.humboldt.sdk.trace;
    requires transitive io.vidocq.humboldt.propagator.w3c;
    requires transitive io.opentelemetry.api;
    requires transitive io.opentelemetry.context;
    requires transitive jakarta.ws.rs;
    requires jakarta.annotation;  // @Priority on HumboldtSpanFinalizer + Client*Filter
    requires java.logging;
    // M7c.12 — MicroProfile Rest Client: optional. HumboldtMpRestClientListener
    // is only invoked if MP Rest Client is on the runtime classpath (typically
    // via cyrano-core). `requires static` = compile-time only, not a runtime dep.
    requires static microprofile.rest.client.api;

    exports io.vidocq.humboldt.rest;

    // M7c.7 — Auto-discovery by CassiniClientBuilder (and any compatible JAX-RS Client)
    // which scans ServiceLoader<Feature> at build() — automatically instruments outbound
    // requests with a kind=CLIENT span (conformant with MP Telemetry §3.2).
    provides jakarta.ws.rs.core.Feature
            with io.vidocq.humboldt.rest.HumboldtClientTracingFeature;

    // M7c.12 — Auto-instrumentation of MP Rest Clients via the standard SPI
    // RestClientListener.onNewClient() (MP Rest Client 4.0 spec §10.2). Implemented
    // by Cyrano in CyranoRestClientBuilder.build().
    provides org.eclipse.microprofile.rest.client.spi.RestClientListener
            with io.vidocq.humboldt.rest.HumboldtMpRestClientListener;
}
