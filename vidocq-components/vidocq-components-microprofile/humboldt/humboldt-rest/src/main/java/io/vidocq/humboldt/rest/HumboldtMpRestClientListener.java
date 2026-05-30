package io.vidocq.humboldt.rest;

import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.rest.client.spi.RestClientListener;

/**
 * {@link RestClientListener} that registers {@link HumboldtClientRequestFilter} and
 * {@link HumboldtClientResponseFilter} on every MP Rest Client created via
 * {@link org.eclipse.microprofile.rest.client.RestClientBuilder} — auto-instrumentation
 * conformant with MP Telemetry 2.1 §3.2 (the TCK does {@code RestClientBuilder.newBuilder()
 * .baseUri(...).build(MyClient.class)} without an explicit {@code .register()} and expects
 * {@code kind=CLIENT} spans to be set around each call).
 *
 * <p>Discovered via {@code META-INF/services/org.eclipse.microprofile.rest.client.spi.RestClientListener}.
 * MP Rest Client 4.0 spec §10.2: {@code onNewClient()} is invoked before the build by
 * all registered listeners, in classpath order. Cyrano implements this SPI
 * (see {@code CyranoRestClientBuilder.build()}).</p>
 *
 * <p>Symmetric to {@link HumboldtClientTracingFeature} which does the same for
 * standard JAX-RS Client via {@code ServiceLoader<Feature>} (mechanism M7c.6 commit #4).
 * Together they cover both HTTP client APIs of the Jakarta/MP ecosystem.</p>
 */
public class HumboldtMpRestClientListener implements RestClientListener {

    @Override
    public void onNewClient(Class<?> serviceInterface, RestClientBuilder builder) {
        // Like jakarta.ws.rs.client.Configurable, RestClientBuilder extends
        // Configurable<RestClientBuilder>. register(Class) instantiates the class and
        // registers it as a provider (a filter here). MP Rest Client spec §6 guarantees
        // that the same provider class is only registered once (duplicates ignored).
        builder.register(HumboldtClientRequestFilter.class);
        builder.register(HumboldtClientResponseFilter.class);
    }
}
