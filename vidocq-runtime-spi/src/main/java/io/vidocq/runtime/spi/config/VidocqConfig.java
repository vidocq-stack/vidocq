package io.vidocq.runtime.spi.config;

import java.util.List;
import java.util.Optional;

/**
 * Access point to Vidocq configuration.
 *
 * <p>Properties are resolved by querying registered {@link ConfigSource} instances,
 * sorted by descending {@link ConfigSource#ordinal()} (first wins).</p>
 *
 * <p>The API is intentionally aligned with MicroProfile Config to ease future migration.</p>
 */
public interface VidocqConfig {

    /**
     * Retrieves the raw value of a property.
     */
    Optional<String> getValue(String key);

    /**
     * Retrieves a typed value.
     *
     * <p>A {@link Converter} must be registered for {@code type}.</p>
     */
    <T> Optional<T> getValue(String key, Class<T> type);

    /**
     * Retrieves a typed value with a default value.
     */
    default <T> T getValue(String key, Class<T> type, T defaultValue) {
        return getValue(key, type).orElse(defaultValue);
    }

    /**
     * Retrieves a list of typed values.
     *
     * <p>Values are comma-separated; use {@code \,} to escape a comma.</p>
     */
    <T> List<T> getValues(String key, Class<T> elementType);

    /**
     * All known keys across all sources.
     */
    Iterable<String> getPropertyNames();

    /**
     * Currently registered configuration sources, sorted by descending ordinal.
     */
    Iterable<ConfigSource> getConfigSources();
}
