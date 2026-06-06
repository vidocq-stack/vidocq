package io.vidocq.runtime.examples.knock;

import io.vidocq.runtime.core.Vidocq;

import java.io.IOException;
import java.util.logging.LogManager;

/**
 * Boots the Vidocq runtime for the Health example. Used as the jlink image main class to validate,
 * on the strict module path, that {@code GET /api/health} serves WITHOUT knock-jaxrs opening its
 * package to vauban-core: the generated {@code _VaubanComponents} provider instantiates
 * {@code KnockHealthResource} and writes its {@code @Inject HealthCheckRegistry} field in-module.
 * A 200 response proves the field was injected with zero reflection / zero opens.
 */
public class KnockHealthExampleApp {
    public static void main(String[] args) throws IOException {
        LogManager.getLogManager().readConfiguration(
                KnockHealthExampleApp.class.getResourceAsStream("/logging.properties"));
        Vidocq.main(args);
    }
}
