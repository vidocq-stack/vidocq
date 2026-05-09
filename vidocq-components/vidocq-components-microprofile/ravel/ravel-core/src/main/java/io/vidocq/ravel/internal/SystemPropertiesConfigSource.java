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
 * {@link ConfigSource} adossée à {@link System#getProperties()} — ordinal 400 par défaut
 * (MP Config 3.1 §3.4).
 *
 * <p>Aucun cache local : les System properties sont mutables runtime, et chaque appel
 * à {@code getValue}/{@code getPropertyNames}/{@code getProperties} relit l'état courant.</p>
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
        // Snapshot défensif : éviter de retourner directement la Properties
        // mutable du système.
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
        // §3.4 — la valeur du système {@code config_ordinal} (s'il y en a une)
        // remplace l'ordinal par défaut.
        String override = System.getProperty("config_ordinal");
        if (override != null) {
            try {
                return Integer.parseInt(override.trim());
            } catch (NumberFormatException ignored) {
                // valeur non parsable → ordinal par défaut
            }
        }
        return ORDINAL;
    }
}
