package fr.vidocq.vidocq.spi;

import java.util.Optional;

/**
 * Configuration accessible aux extensions Vidocq.
 * <p>
 * Les propriétés sont résolues depuis (par ordre de priorité) :
 * <ol>
 *   <li>Propriétés système ({@code -Dkey=value})</li>
 *   <li>Variables d'environnement</li>
 *   <li>Fichier {@code vidocq.properties} du classpath</li>
 * </ol>
 *
 * <p><b>Configuration accessible to Vidocq extensions.</b>
 * Properties are resolved from system properties, environment variables,
 * and {@code vidocq.properties} classpath resource (in that order).</p>
 */
public interface VidocqConfiguration {

    /**
     * Récupère une propriété par sa clé.
     * <p>Retrieve a property by key.</p>
     */
    Optional<String> property(String key);

    /**
     * Récupère une propriété avec une valeur par défaut.
     * <p>Retrieve a property with a default value.</p>
     */
    default String property(String key, String defaultValue) {
        return property(key).orElse(defaultValue);
    }

    /**
     * Récupère un port pour une extension donnée.
     * <p>Clé consultée : {@code vidocq.<extensionName>.port}</p>
     * <p>Retrieve a port for a given extension.</p>
     */
    default int portFor(String extensionName, int defaultPort) {
        return property("vidocq." + extensionName + ".port")
                .map(Integer::parseInt)
                .orElse(defaultPort);
    }
}
