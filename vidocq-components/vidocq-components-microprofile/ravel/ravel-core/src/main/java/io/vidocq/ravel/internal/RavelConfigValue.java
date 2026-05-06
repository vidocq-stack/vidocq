/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.internal;

import org.eclipse.microprofile.config.ConfigValue;

import java.util.Objects;

/**
 * Implémentation immuable de {@link ConfigValue} (MP Config 3.1 §2.1.5).
 *
 * <p>Pour une clé absente, {@code Config.getConfigValue(name)} doit retourner un
 * {@code ConfigValue} non-null dont seul le {@code name} est renseigné — utiliser
 * {@link #absent(String)} pour obtenir cette instance sentinelle.</p>
 */
public record RavelConfigValue(
        String name,
        String value,
        String rawValue,
        String sourceName,
        int sourceOrdinal
) implements ConfigValue {

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getValue() {
        return value;
    }

    @Override
    public String getRawValue() {
        return rawValue;
    }

    @Override
    public String getSourceName() {
        return sourceName;
    }

    @Override
    public int getSourceOrdinal() {
        return sourceOrdinal;
    }

    /**
     * Sentinelle pour une clé absente — value/rawValue/sourceName=null, sourceOrdinal=0.
     *
     * @param name nom de la propriété recherchée (jamais {@code null})
     */
    public static RavelConfigValue absent(String name) {
        Objects.requireNonNull(name, "name");
        return new RavelConfigValue(name, null, null, null, 0);
    }
}
