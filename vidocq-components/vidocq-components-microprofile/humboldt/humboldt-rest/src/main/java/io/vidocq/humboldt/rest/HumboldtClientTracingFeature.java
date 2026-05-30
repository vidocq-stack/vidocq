package io.vidocq.humboldt.rest;

import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;

/**
 * {@link Feature} that registers {@link HumboldtClientRequestFilter} and
 * {@link HumboldtClientResponseFilter} on any JAX-RS {@code Client} — discovered
 * via {@code META-INF/services/jakarta.ws.rs.core.Feature} for auto-instrumentation
 * conformant with MP Telemetry 2.1 §3.2 (the TCK does {@code ClientBuilder.newClient()}
 * without an explicit {@code .register()} and expects CLIENT spans to be set).
 *
 * <p>Convention: returns {@code true} to signal that the Feature has configured
 * itself successfully; the caller (CassiniClientBuilder) currently ignores the
 * return value, but other JAX-RS implementations read it to enable/disable the Feature.</p>
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
