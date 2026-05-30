package io.vidocq.runtime.core.config;

import io.vidocq.runtime.spi.config.ConfigSource;

import java.util.HashSet;
import java.util.Set;

/**
 * Source reading {@link System#getProperties() system properties}.
 * Ordinal 400 (MicroProfile Config default).
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
