package io.vidocq.mpserver.core.config;

import io.vidocq.mpserver.spi.config.ConfigSource;

import java.util.HashSet;
import java.util.Set;

/**
 * Source lisant les {@link System#getProperties() propriétés système}.
 * Ordinal 400 (défaut MicroProfile Config).
 */
public final class SystemPropertiesConfigSource implements ConfigSource {

    @Override
    public String getName() {
        return "SystemProperties";
    }

    @Override
    public int getOrdinal() {
        return 400;
    }

    @Override
    public String getValue(String key) {
        return System.getProperty(key);
    }

    @Override
    public Set<String> getPropertyNames() {
        Set<String> names = new HashSet<>();
        for (Object k : System.getProperties().keySet()) {
            names.add(String.valueOf(k));
        }
        return names;
    }
}
