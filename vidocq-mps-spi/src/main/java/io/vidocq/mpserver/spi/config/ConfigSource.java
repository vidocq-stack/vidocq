package io.vidocq.mpserver.spi.config;

import java.util.Map;
import java.util.Set;

/**
 * Source de configuration Vidocq.
 * <p>
 * Les implémentations sont découvertes via {@link java.util.ServiceLoader} et triées par
 * {@link #ordinal()} décroissant lors de la résolution.
 * </p>
 * <p>Conventions d'ordinal (alignées MicroProfile Config) :</p>
 * <ul>
 *   <li>400 — propriétés système ({@code -Dkey=value})</li>
 *   <li>300 — variables d'environnement</li>
 *   <li>100 — fichier {@code vidocq.properties} du classpath</li>
 *   <li>0..99 — défauts applicatifs, overrides custom au-delà de 400</li>
 * </ul>
 *
 * <p><b>Vidocq configuration source.</b>
 * Implementations are discovered via {@link java.util.ServiceLoader} and sorted by
 * descending {@link #ordinal()} during resolution.</p>
 */
public interface ConfigSource {

    /**
     * Nom de la source (utilisé pour le diagnostic).
     * <p>Source name (used for diagnostics).</p>
     */
    String getName();

    /**
     * Priorité de la source (plus grand = gagne).
     * <p>Source priority (higher wins).</p>
     */
    int getOrdinal();

    /**
     * Valeur brute pour une clé, ou {@code null} si inconnue.
     * <p>Raw value for a key, or {@code null} if unknown.</p>
     */
    String getValue(String key);

    /**
     * Ensemble des clés connues par cette source.
     * <p>Set of keys known by this source.</p>
     */
    Set<String> getPropertyNames();

    /**
     * Vue en lecture des propriétés. Peut être coûteuse pour certaines sources.
     * <p>Read-only view of properties. May be expensive for some sources.</p>
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
     * Alias {@link #getOrdinal()} pour cohérence avec Comparable.
     * <p>Alias for {@link #getOrdinal()}.</p>
     */
    default int ordinal() {
        return getOrdinal();
    }
}
