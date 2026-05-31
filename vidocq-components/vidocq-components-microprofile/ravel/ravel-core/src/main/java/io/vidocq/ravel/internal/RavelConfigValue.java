/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.internal;

import org.eclipse.microprofile.config.ConfigValue;

import java.util.Objects;

/**
 * Immutable {@link ConfigValue} implementation (MP Config 3.1 §2.1.5).
 *
 * <p>For a missing key, {@code Config.getConfigValue(name)} must return a
 * non-null {@code ConfigValue} where only {@code name} is set. Use
 * {@link #absent(String)} for that sentinel instance.</p>
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
     * Sentinel for a missing key: value/rawValue/sourceName=null, sourceOrdinal=0.
     *
     * @param name looked-up property name (never {@code null})
     */
    public static RavelConfigValue absent(String name) {
        Objects.requireNonNull(name, "name");
        return new RavelConfigValue(name, null, null, null, 0);
    }
}
