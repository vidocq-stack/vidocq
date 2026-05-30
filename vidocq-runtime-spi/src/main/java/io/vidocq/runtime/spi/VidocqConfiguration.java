package io.vidocq.runtime.spi;

import java.util.Optional;

/**
 * Configuration accessible to Vidocq extensions.
 * <p>
 * The properties are resolved from (in order of priority):
 * <ol>
 *   <li>System properties ({@code -Dkey=value})</li>
 *   <li>Environment variables</li>
 *   <li>Classpath {@code vidocq.properties} file</li>
 * </ol>
 *
 * <p><b>Configuration accessible to Vidocq extensions.</b>
 * Properties are resolved from system properties, environment variables,
 * and {@code vidocq.properties} classpath resource (in that order).</p>
 */
public interface VidocqConfiguration {

    /**
     * Retrieves a property by its key.
     * <p>Retrieve a property by key.</p>
     */
    Optional<String> property(String key);

    /**
     * Retrieves a property with a default value.
     * <p>Retrieve a property with a default value.</p>
     */
    default String property(String key, String defaultValue) {
        return property(key).orElse(defaultValue);
    }

    /**
     * Retrieves a port for a given extension.
     * <p>Key consulted: {@code vidocq.<extensionName>.port}</p>
     * <p>Retrieve a port for a given extension.</p>
     */
    default int portFor(String extensionName, int defaultPort) {
        return property("vidocq." + extensionName + ".port")
                .map(Integer::parseInt)
                .orElse(defaultPort);
    }
}
