package io.vidocq.humboldt.runtime;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Lecteur de configuration env vars + system properties.
 *
 * <p>Convention OTel : la clé d'env est en {@code SCREAMING_SNAKE_CASE}, la clé
 * de system property est l'équivalent {@code lower.dot.case}. Exemple :
 * {@code OTEL_SERVICE_NAME} ↔ {@code otel.service.name}. Priorité aux env vars
 * (alignement avec l'impl OTel reference), fallback system property.</p>
 *
 * <p>Construit par défaut sur {@link System#getenv()} et
 * {@link System#getProperties()}, mais peut être instancié avec une map custom
 * pour tests (cf. {@link #of(Map, Map)}).</p>
 */
public final class EnvConfig {

    private final Function<String, String> envLookup;
    private final Function<String, String> propLookup;

    private EnvConfig(Function<String, String> envLookup, Function<String, String> propLookup) {
        this.envLookup = envLookup;
        this.propLookup = propLookup;
    }

    public static EnvConfig system() {
        return new EnvConfig(System::getenv, System::getProperty);
    }

    public static EnvConfig of(Map<String, String> env, Map<String, String> props) {
        return new EnvConfig(env::get, props::get);
    }

    /**
     * @param envKey clé en SCREAMING_SNAKE_CASE (ex. {@code OTEL_SERVICE_NAME})
     * @return la valeur résolue (env > prop), trimmée, ou empty si absente
     */
    public Optional<String> get(String envKey) {
        String fromEnv = envLookup.apply(envKey);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Optional.of(fromEnv.strip());
        }
        String propKey = envToProp(envKey);
        String fromProp = propLookup.apply(propKey);
        if (fromProp != null && !fromProp.isBlank()) {
            return Optional.of(fromProp.strip());
        }
        return Optional.empty();
    }

    public String getOrDefault(String envKey, String def) {
        return get(envKey).orElse(def);
    }

    public boolean getBoolean(String envKey, boolean def) {
        return get(envKey).map(v -> v.equalsIgnoreCase("true") || v.equals("1")).orElse(def);
    }

    public long getLong(String envKey, long def) {
        return get(envKey).map(v -> {
            try { return Long.parseLong(v); }
            catch (NumberFormatException e) { return def; }
        }).orElse(def);
    }

    public double getDouble(String envKey, double def) {
        return get(envKey).map(v -> {
            try { return Double.parseDouble(v); }
            catch (NumberFormatException e) { return def; }
        }).orElse(def);
    }

    /**
     * @return {@code OTEL_SERVICE_NAME} → {@code otel.service.name}
     */
    static String envToProp(String envKey) {
        return envKey.toLowerCase(Locale.ROOT).replace('_', '.');
    }
}
