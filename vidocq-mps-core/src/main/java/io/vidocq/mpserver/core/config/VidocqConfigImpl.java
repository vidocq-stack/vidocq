package io.vidocq.mpserver.core.config;

import io.vidocq.mpserver.spi.config.ConfigSource;
import io.vidocq.mpserver.spi.config.Converter;
import io.vidocq.mpserver.spi.config.VidocqConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Implémentation par défaut de {@link VidocqConfig}.
 * <p>
 * Charge les sources via {@link ServiceLoader} et les trie par ordinal décroissant.
 * Résolution first-wins.
 * </p>
 */
public final class VidocqConfigImpl implements VidocqConfig {

    private final List<ConfigSource> sources;

    public VidocqConfigImpl() {
        this(discoverSources());
    }

    public VidocqConfigImpl(List<ConfigSource> sources) {
        List<ConfigSource> copy = new ArrayList<>(sources);
        copy.sort(Comparator.comparingInt(ConfigSource::getOrdinal).reversed());
        this.sources = Collections.unmodifiableList(copy);
    }

    @Override
    public Optional<String> getValue(String key) {
        for (ConfigSource s : sources) {
            String v = s.getValue(key);
            if (v != null) return Optional.of(v);
        }
        return Optional.empty();
    }

    @Override
    public <T> Optional<T> getValue(String key, Class<T> type) {
        return getValue(key).map(raw -> {
            Converter<T> c = Converters.forType(type);
            return c.convert(raw);
        });
    }

    @Override
    public <T> List<T> getValues(String key, Class<T> elementType) {
        return getValue(key)
                .map(raw -> {
                    Converter<T> c = Converters.forType(elementType);
                    List<T> out = new ArrayList<>();
                    for (String item : splitList(raw)) {
                        out.add(c.convert(item));
                    }
                    return out;
                })
                .orElseGet(List::of);
    }

    @Override
    public Iterable<String> getPropertyNames() {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        for (ConfigSource s : sources) {
            all.addAll(s.getPropertyNames());
        }
        return all;
    }

    @Override
    public Iterable<ConfigSource> getConfigSources() {
        return sources;
    }

    private static List<ConfigSource> discoverSources() {
        List<ConfigSource> discovered = new ArrayList<>();
        for (ConfigSource s : ServiceLoader.load(ConfigSource.class)) {
            discovered.add(s);
        }
        return discovered;
    }

    private static List<String> splitList(String raw) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escape = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escape) {
                current.append(c);
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == ',') {
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        out.add(current.toString());
        return out;
    }
}
