package io.vidocq.mpserver.examples.rest;

import io.vidocq.mpserver.core.Vidocq;

import java.io.IOException;
import java.util.logging.LogManager;

/**
 * Point d'entrée de l'application REST d'exemple.
 * <p>
 * Démarre Vidocq avec l'extension REST qui expose
 * les ressources JAX-RS découvertes via CDI.
 * </p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * java -m io.vidocq.mpserver.examples.rest/io.vidocq.mpserver.examples.rest.RestExampleApp
 * }</pre>
 *
 * <p>Endpoints disponibles :</p>
 * <ul>
 *   <li>{@code GET /hello} — message texte</li>
 *   <li>{@code GET /hello/json} — message JSON</li>
 * </ul>
 */
public class RestExampleApp {

    public static void main(String[] args) throws IOException {
        LogManager.getLogManager().readConfiguration(
                RestExampleApp.class.getResourceAsStream("/logging.properties"));
        Vidocq.main(args);
    }
}
