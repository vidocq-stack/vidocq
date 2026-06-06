package io.vidocq.runtime.examples.knock;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

/**
 * Application-supplied liveness probe. Discovered by the knock-cdi-vauban
 * {@code BuildCompatibleExtension} (Vauban BCE) and auto-registered in the
 * {@code HealthCheckRegistry}. Proves {@code @Liveness} discovery on the strict module path.
 */
@Liveness
@ApplicationScoped
public class AppLivenessCheck implements HealthCheck {

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.up("app");
    }
}
