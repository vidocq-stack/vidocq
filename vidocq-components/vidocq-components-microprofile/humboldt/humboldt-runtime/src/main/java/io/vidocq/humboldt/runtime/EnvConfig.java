package io.vidocq.humboldt.runtime;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Env vars + system properties configuration reader.
 *
 * <p>OTel convention: the env key is in {@code SCREAMING_SNAKE_CASE}, the
 * system property key is the {@code lower.dot.case} equivalent. Example:
 * {@code OTEL_SERVICE_NAME} ↔ {@code otel.service.name}. Env vars take
 * priority (aligned with the OTel reference implementation), falling back
 * to system properties.</p>
 *
 * <p>Backed by default on {@link System#getenv()} and
 * {@link System#getProperties()}, but can be instantiated with a custom map
 * for tests (see {@link #of(Map, Map)}).</p>
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
     * @param envKey key in SCREAMING_SNAKE_CASE (e.g. {@code OTEL_SERVICE_NAME})
     * @return the resolved value (env &gt; prop), trimmed, or empty if absent
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
