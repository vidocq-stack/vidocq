package io.vidocq.humboldt.rest;

import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;

/**
 * {@link Feature} qui enregistre {@link HumboldtClientRequestFilter} et
 * {@link HumboldtClientResponseFilter} sur tout {@code Client} JAX-RS — découverte
 * via {@code META-INF/services/jakarta.ws.rs.core.Feature} pour auto-instrumentation
 * conforme MP Telemetry 2.1 §3.2 (les TCK font {@code ClientBuilder.newClient()}
 * sans {@code .register()} explicite et attendent que les spans CLIENT soient posés).
 *
 * <p>Convention : retourne {@code true} pour signaler que le Feature s'est bien
 * configuré ; le caller (CassiniClientBuilder) ignore actuellement la valeur de retour
 * mais d'autres impls JAX-RS la lisent pour activer/désactiver le Feature.</p>
 */
public class HumboldtClientTracingFeature implements Feature {

    @Override
    public boolean configure(FeatureContext context) {
        if (!context.getConfiguration().isRegistered(HumboldtClientRequestFilter.class)) {
            context.register(HumboldtClientRequestFilter.class);
        }
        if (!context.getConfiguration().isRegistered(HumboldtClientResponseFilter.class)) {
            context.register(HumboldtClientResponseFilter.class);
        }
        return true;
    }
}
