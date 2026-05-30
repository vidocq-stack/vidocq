package io.vidocq.runtime.examples.rest;

import io.vidocq.runtime.core.Vidocq;

import java.io.IOException;
import java.util.logging.LogManager;

/**
 * Entry point of the example todo-list application — static UI served by
 * Chappe + REST endpoints {@code /api/todos} provided by Cassini, with
 * in-memory storage in a Vauban CDI service.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * java -m io.vidocq.runtime.examples.rest/io.vidocq.runtime.examples.rest.RestExampleApp
 * }</pre>
 *
 * <p>Once started:</p>
 * <ul>
 *   <li>UI → {@code http://localhost:8080/}</li>
 *   <li>API → {@code http://localhost:8080/api/todos}</li>
 * </ul>
 */
public class RestExampleApp {

    public static void main(String[] args) throws IOException {
        LogManager.getLogManager().readConfiguration(
                RestExampleApp.class.getResourceAsStream("/logging.properties"));
        Vidocq.main(args);
    }
}
