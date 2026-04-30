package io.vidocq.mpserver.examples.rest;

import io.vidocq.mpserver.core.Vidocq;

import java.io.IOException;
import java.util.logging.LogManager;

/**
 * Point d'entrée de l'application todo-list d'exemple — UI statique servie par
 * Chappe + endpoints REST {@code /api/todos} fournis par Cassini avec stockage
 * en mémoire dans un service CDI Vauban.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * java -m io.vidocq.mpserver.examples.rest/io.vidocq.mpserver.examples.rest.RestExampleApp
 * }</pre>
 *
 * <p>Une fois démarré :</p>
 * <ul>
 *   <li>UI  → {@code http://localhost:8080/}</li>
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
