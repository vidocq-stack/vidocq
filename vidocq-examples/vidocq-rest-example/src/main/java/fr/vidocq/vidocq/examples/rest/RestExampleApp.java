package fr.vidocq.vidocq.examples.rest;

import fr.vidocq.vidocq.core.Vidocq;

/**
 * Point d'entrée de l'application REST d'exemple.
 * <p>
 * Démarre Vidocq avec l'extension REST qui expose
 * les ressources JAX-RS découvertes via CDI.
 * </p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * java -m fr.vidocq.vidocq.examples.rest/fr.vidocq.vidocq.examples.rest.RestExampleApp
 * }</pre>
 *
 * <p>Endpoints disponibles :</p>
 * <ul>
 *   <li>{@code GET /hello} — message texte</li>
 *   <li>{@code GET /hello/json} — message JSON</li>
 * </ul>
 */
public class RestExampleApp {

    public static void main(String[] args) {
        Vidocq.main(args);
    }
}
