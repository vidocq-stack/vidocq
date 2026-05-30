package io.vidocq.runtime.spi.config;

import java.util.Map;
import java.util.Set;

/**
 * Vidocq configuration source.
 *
 * <p>Implementations are discovered via {@link java.util.ServiceLoader} and sorted by
 * descending {@link #ordinal()} during resolution.</p>
 * <p>Ordinal conventions (MicroProfile Config aligned):</p>
 * <ul>
 *   <li>400 — system properties ({@code -Dkey=value})</li>
 *   <li>300 — environment variables</li>
 *   <li>100 — classpath {@code vidocq.properties} file</li>
 *   <li>0..99 — application defaults; custom overrides may also use values above 400</li>
 * </ul>
 */
public interface ConfigSource {

    /**
     * Source name, used for diagnostics.
     */
    String getName();

    /**
     * Source priority (higher wins).
     */
    int getOrdinal();

    /**
     * Raw value for a key, or {@code null} if unknown.
     */
    String getValue(String key);

    /**
     * Set of keys known by this source.
     */
    Set<String> getPropertyNames();

    /**
     * Read-only view of properties.
     *
     * <p>May be expensive for some sources.</p>
     */
    default Map<String, String> getProperties() {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        for (String name : getPropertyNames()) {
            String v = getValue(name);
            if (v != null) {
                map.put(name, v);
            }
        }
        return java.util.Collections.unmodifiableMap(map);
    }

    /**
     * Alias for {@link #getOrdinal()}.
     */
    default int ordinal() {
        return getOrdinal();
    }
}
