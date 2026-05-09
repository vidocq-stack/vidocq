/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.internal;

import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * {@link ConfigSource} adossée à {@link System#getenv()} — ordinal 300 par défaut
 * (MP Config 3.1 §3.4 / §7.6).
 *
 * <p>La spec §7.6 définit 3 formes de clé essayées dans l'ordre :</p>
 * <ol>
 *   <li>Match exact (clé telle quelle).</li>
 *   <li>Caractères non-alphanumériques (sauf {@code _}) remplacés par {@code _}.</li>
 *   <li>Étape 2 puis conversion en majuscules.</li>
 * </ol>
 *
 * <p>Ainsi {@code "com.ACME.size"} cherche successivement {@code "com.ACME.size"},
 * {@code "com_ACME_size"}, puis {@code "COM_ACME_SIZE"}.</p>
 */
public final class EnvironmentVariablesConfigSource implements ConfigSource {

    private static final int ORDINAL = 300;
    private static final String NAME = "EnvironmentVariablesConfigSource";

    public EnvironmentVariablesConfigSource() {
        // ServiceLoader-friendly
    }

    @Override
    public String getValue(String propertyName) {
        return lookup(propertyName, System::getenv);
    }

    @Override
    public Set<String> getPropertyNames() {
        // §7.6 — keys are returned as is (raw env var names).
        return System.getenv().keySet();
    }

    @Override
    public Map<String, String> getProperties() {
        return System.getenv();
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getOrdinal() {
        // §3.4 — la valeur de la propriété {@code config_ordinal} dans la source
        // elle-même remplace l'ordinal par défaut.
        String override = System.getenv("config_ordinal");
        if (override != null) {
            try {
                return Integer.parseInt(override.trim());
            } catch (NumberFormatException ignored) {
                // valeur non parsable → ordinal par défaut
            }
        }
        return ORDINAL;
    }

    /**
     * Implémentation testable du mapping §7.6 — délègue le lookup réel via
     * la {@link Function} {@code env}.
     *
     * <p>Visible package-private pour les tests unitaires sans dépendance à
     * l'environnement système réel.</p>
     */
    static String lookup(String propertyName, Function<String, String> env) {
        // Forme 1 : exact
        String value = env.apply(propertyName);
        if (value != null) {
            return value;
        }
        // Forme 2 : non-alphanum → _, casse préservée
        String envFormat = toEnvFormat(propertyName);
        if (!envFormat.equals(propertyName)) {
            value = env.apply(envFormat);
            if (value != null) {
                return value;
            }
        }
        // Forme 3 : forme 2 + UPPER
        String upper = envFormat.toUpperCase(Locale.ROOT);
        if (!upper.equals(envFormat)) {
            value = env.apply(upper);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Remplace tout caractère non alphanumérique (et différent de {@code _})
     * par {@code _}. Visible package-private pour testabilité.
     */
    static String toEnvFormat(String propertyName) {
        var sb = new StringBuilder(propertyName.length());
        for (int i = 0; i < propertyName.length(); i++) {
            char c = propertyName.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}
