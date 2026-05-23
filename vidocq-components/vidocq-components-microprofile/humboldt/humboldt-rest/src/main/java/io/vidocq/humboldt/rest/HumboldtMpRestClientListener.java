package io.vidocq.humboldt.rest;

import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.rest.client.spi.RestClientListener;

/**
 * {@link RestClientListener} qui enregistre {@link HumboldtClientRequestFilter} et
 * {@link HumboldtClientResponseFilter} sur tout Rest Client MP créé via
 * {@link org.eclipse.microprofile.rest.client.RestClientBuilder} — auto-instrumentation
 * conforme MP Telemetry 2.1 §3.2 (les TCK font {@code RestClientBuilder.newBuilder()
 * .baseUri(...).build(MyClient.class)} sans {@code .register()} explicite et attendent
 * que des spans {@code kind=CLIENT} soient posés autour de chaque appel).
 *
 * <p>Découvert via {@code META-INF/services/org.eclipse.microprofile.rest.client.spi.RestClientListener}.
 * Spec MP Rest Client 4.0 §10.2 : {@code onNewClient()} est invoqué avant le build par
 * tous les listeners enregistrés, dans l'ordre du classpath. Cyrano implémente ce SPI
 * (cf. {@code CyranoRestClientBuilder.build()}).</p>
 *
 * <p>Symétrique de {@link HumboldtClientTracingFeature} qui fait la même chose pour
 * JAX-RS Client standard via {@code ServiceLoader<Feature>} (mécanisme M7c.6 commit #4).
 * Les deux ensembles couvrent les deux APIs HTTP client de l'écosystème Jakarta/MP.</p>
 */
public class HumboldtMpRestClientListener implements RestClientListener {

    @Override
    public void onNewClient(Class<?> serviceInterface, RestClientBuilder builder) {
        // Comme jakarta.ws.rs.client.Configurable, RestClientBuilder hérite de
        // Configurable<RestClientBuilder>. register(Class) instancie la classe et
        // l'enregistre comme provider (filter ici). MP Rest Client spec §6 garantit
        // qu'un même provider class n'est enregistré qu'une fois (doublons ignorés).
        builder.register(HumboldtClientRequestFilter.class);
        builder.register(HumboldtClientResponseFilter.class);
    }
}
