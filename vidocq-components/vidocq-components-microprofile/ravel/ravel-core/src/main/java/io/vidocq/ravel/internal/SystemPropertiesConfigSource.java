/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.internal;

import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * {@link ConfigSource} backed by {@link System#getProperties()} with default ordinal
 * 400 (MP Config 3.1 §3.4).
 *
 * <p>No local cache: system properties are mutable at runtime, so each call to
 * {@code getValue}/{@code getPropertyNames}/{@code getProperties} reads current state.</p>
 */
public final class SystemPropertiesConfigSource implements ConfigSource {

    private static final int ORDINAL = 400;
    private static final String NAME = "SystemPropertiesConfigSource";

    public SystemPropertiesConfigSource() {
        // ServiceLoader-friendly
    }

    @Override
    public String getValue(String propertyName) {
        return System.getProperty(propertyName);
    }

    @Override
    public Set<String> getPropertyNames() {
        return System.getProperties().stringPropertyNames();
    }

    @Override
    public Map<String, String> getProperties() {
        // Defensive snapshot: do not return mutable system Properties directly.
        Properties sys = System.getProperties();
        var copy = new HashMap<String, String>(sys.size());
        for (String name : sys.stringPropertyNames()) {
            String value = sys.getProperty(name);
            if (value != null) {
                copy.put(name, value);
            }
        }
        return Map.copyOf(copy);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getOrdinal() {
        // §3.4: system property {@code config_ordinal} overrides default ordinal.
        String override = System.getProperty("config_ordinal");
        if (override != null) {
            try {
                return Integer.parseInt(override.trim());
            } catch (NumberFormatException ignored) {
                // Non-parsable value: keep default ordinal.
            }
        }
        return ORDINAL;
    }
}
