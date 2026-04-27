package io.vidocq.mpserver.spi.config;

import java.util.List;
import java.util.Optional;

/**
 * Point d'accès à la configuration Vidocq.
 * <p>
 * Les propriétés sont résolues en interrogeant les {@link ConfigSource} enregistrées,
 * triées par {@link ConfigSource#ordinal()} décroissant (first-wins).
 * </p>
 * <p>
 * L'API est volontairement alignée sur MicroProfile Config afin de permettre une
 * migration future sans changement de code côté utilisateur.
 * </p>
 *
 * <p><b>Vidocq configuration access point.</b>
 * Properties are resolved by querying registered {@link ConfigSource} sorted
 * by descending {@link ConfigSource#ordinal()}. API is intentionally aligned
 * with MicroProfile Config to ease future migration.</p>
 */
public interface VidocqConfig {

    /**
     * Récupère la valeur brute d'une propriété.
     * <p>Retrieve the raw value of a property.</p>
     */
    Optional<String> getValue(String key);

    /**
     * Récupère une valeur typée. Un {@link Converter} doit être enregistré pour {@code type}.
     * <p>Retrieve a typed value. A {@link Converter} must be registered for {@code type}.</p>
     */
    <T> Optional<T> getValue(String key, Class<T> type);

    /**
     * Récupère une valeur typée avec valeur par défaut.
     * <p>Retrieve a typed value with default.</p>
     */
    default <T> T getValue(String key, Class<T> type, T defaultValue) {
        return getValue(key, type).orElse(defaultValue);
    }

    /**
     * Récupère une liste de valeurs typées (séparateur {@code ,}, échappement {@code \\,}).
     * <p>Retrieve a list of typed values (comma-separated, {@code \\,} to escape).</p>
     */
    <T> List<T> getValues(String key, Class<T> elementType);

    /**
     * Liste de toutes les clés connues à travers l'ensemble des sources.
     * <p>All known keys across all sources.</p>
     */
    Iterable<String> getPropertyNames();

    /**
     * Sources de configuration actuellement enregistrées, triées par ordinal décroissant.
     * <p>Currently registered config sources, sorted by descending ordinal.</p>
     */
    Iterable<ConfigSource> getConfigSources();
}
