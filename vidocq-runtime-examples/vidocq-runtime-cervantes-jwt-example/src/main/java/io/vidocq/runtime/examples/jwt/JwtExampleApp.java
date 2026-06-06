package io.vidocq.runtime.examples.jwt;

import io.vidocq.runtime.core.Vidocq;

import java.io.IOException;
import java.util.logging.LogManager;

/**
 * Boots the Vidocq runtime for the JWT example. Used as the jlink image main class to validate,
 * on the strict module path, that a JWT-secured endpoint serves WITHOUT the cervantes modules
 * opening their packages to vauban-core (the generated {@code _VaubanComponents} provider supplies
 * in-module instantiation instead).
 */
public class JwtExampleApp {
    public static void main(String[] args) throws IOException {
        LogManager.getLogManager().readConfiguration(
                JwtExampleApp.class.getResourceAsStream("/logging.properties"));
        Vidocq.main(args);
    }
}
