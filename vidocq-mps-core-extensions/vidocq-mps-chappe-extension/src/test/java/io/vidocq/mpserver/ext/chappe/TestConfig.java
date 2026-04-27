package io.vidocq.mpserver.ext.chappe;

import io.vidocq.mpserver.spi.config.ConfigSource;
import io.vidocq.mpserver.spi.config.VidocqConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Implémentation de {@link VidocqConfig} test-only — évite de croiser une dépendance JPMS
 * vers {@code vidocq-core} depuis le module de test.
 */
final class TestConfig implements VidocqConfig {

    private final Map<String, String> data;

    private TestConfig(Map<String, String> data) {
        this.data = data;
    }

    static TestConfig of(Map<String, String> data) {
        return new TestConfig(data);
    }

    @Override
    public Optional<String> getValue(String key) {
        return Optional.ofNullable(data.get(key));
    }

    @Override
    public <T> Optional<T> getValue(String key, Class<T> type) {
        return getValue(key).map(raw -> convert(raw, type));
    }

    @Override
    public <T> List<T> getValues(String key, Class<T> elementType) {
        Optional<String> raw = getValue(key);
        if (raw.isEmpty()) return List.of();
        List<T> out = new ArrayList<>();
        for (String item : raw.get().split(",", -1)) {
            out.add(convert(item, elementType));
        }
        return out;
    }

    @Override
    public Iterable<String> getPropertyNames() {
        return data.keySet();
    }

    @Override
    public Iterable<ConfigSource> getConfigSources() {
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static <T> T convert(String raw, Class<T> type) {
        if (type == String.class) return (T) raw;
        if (type == Integer.class) return (T) Integer.valueOf(raw);
        if (type == Boolean.class) return (T) Boolean.valueOf(raw);
        throw new IllegalArgumentException("Unsupported type in TestConfig: " + type);
    }
}
