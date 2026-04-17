package fr.vidocq.examples.servlet;

import fr.vidocq.vidocq.core.Vidocq;

import java.io.IOException;
import java.util.logging.LogManager;

/**
 * Point d'entrée de l'exemple Servlet/Chappe.
 *
 * <p>Démarre Vidocq qui découvre via CDI :</p>
 * <ul>
 *   <li>{@link HelloServlet} — servlet public avec paramètre de query</li>
 *   <li>{@link CounterServlet} — incrément dans la session</li>
 *   <li>{@link AdminServlet} — protégé par {@code @ServletSecurity}</li>
 *   <li>{@link LoggingFilter} — filter global qui journalise chaque requête</li>
 *   <li>{@link StartupListener} — installe le {@code SecurityProvider} et journalise
 *       les événements de cycle de vie</li>
 * </ul>
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET /hello?name=alice}</li>
 *   <li>{@code GET /count} (session)</li>
 *   <li>{@code GET /admin} (BASIC auth : alice/admin ou bob/user)</li>
 * </ul>
 */
public class ServletExampleApp {

    public static void main(String[] args) throws IOException {
        LogManager.getLogManager().readConfiguration(
                ServletExampleApp.class.getResourceAsStream("/logging.properties"));
        Vidocq.main(args);
    }
}
