/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.internal;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Implémentation MP Config 3.1 §2.1 — cascade des {@link ConfigSource} par ordinal
 * décroissant, premier match gagne. Tie-breaking sur l'ordre d'enregistrement.
 *
 * <p>M1 : converter {@link String} uniquement (cf. {@link IdentityStringConverter}).
 * Tout autre type lève {@link IllegalArgumentException} jusqu'à M2.</p>
 *
 * <p><b>Thread-safety</b> : immutable après construction. Les sources mutables
 * (System properties / env vars) sont relues à chaque appel via leur propre
 * implémentation, sans cache local.</p>
 */
public final class RavelConfig implements Config {

    private final List<ConfigSource> sources;
    private final Map<Class<?>, Converter<?>> converters;
    private final ClassLoader classLoader;

    public RavelConfig(List<ConfigSource> sources,
                       Map<Class<?>, Converter<?>> converters,
                       ClassLoader classLoader) {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(converters, "converters");
        // tri stable : ordinal décroissant ; en cas d'égalité, ordre d'enregistrement préservé.
        var sorted = new ArrayList<>(sources);
        sorted.sort(Comparator.comparingInt(ConfigSource::getOrdinal).reversed());
        this.sources = List.copyOf(sorted);
        this.converters = Map.copyOf(converters);
        this.classLoader = classLoader;
    }

    @Override
    public <T> T getValue(String propertyName, Class<T> propertyType) {
        return getOptionalValue(propertyName, propertyType)
                .orElseThrow(() -> new NoSuchElementException(
                        "Property '" + propertyName + "' not found"));
    }

    @Override
    public ConfigValue getConfigValue(String propertyName) {
        Objects.requireNonNull(propertyName, "propertyName");
        for (ConfigSource source : sources) {
            String value = source.getValue(propertyName);
            if (value != null) {
                return new RavelConfigValue(
                        propertyName, value, value, source.getName(), source.getOrdinal());
            }
        }
        return RavelConfigValue.absent(propertyName);
    }

    @Override
    public <T> Optional<T> getOptionalValue(String propertyName, Class<T> propertyType) {
        Objects.requireNonNull(propertyName, "propertyName");
        Objects.requireNonNull(propertyType, "propertyType");
        String raw = lookupRaw(propertyName);
        if (raw == null || raw.isEmpty()) {
            // §2.1.4 — empty string is considered as null.
            return Optional.empty();
        }
        Converter<T> converter = findConverter(propertyType);
        return Optional.ofNullable(converter.convert(raw));
    }

    @Override
    public Iterable<String> getPropertyNames() {
        // Union ordonnée par cascade (ordinal décroissant), pas de duplicate.
        var names = new LinkedHashSet<String>();
        for (ConfigSource source : sources) {
            names.addAll(source.getPropertyNames());
        }
        return Set.copyOf(names);
    }

    @Override
    public Iterable<ConfigSource> getConfigSources() {
        return sources;  // déjà immutable via List.copyOf
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<Converter<T>> getConverter(Class<T> forType) {
        Objects.requireNonNull(forType, "forType");
        return Optional.ofNullable((Converter<T>) converters.get(forType));
    }

    @Override
    public <T> T unwrap(Class<T> type) {
        Objects.requireNonNull(type, "type");
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        throw new IllegalArgumentException(
                "Cannot unwrap " + RavelConfig.class.getName() + " to " + type.getName());
    }

    /** ClassLoader associé — utilisé par le ProviderResolver pour le registre per-CL. */
    ClassLoader getClassLoader() {
        return classLoader;
    }

    // ---------- internals ----------

    private String lookupRaw(String propertyName) {
        for (ConfigSource source : sources) {
            String value = source.getValue(propertyName);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private <T> Converter<T> findConverter(Class<T> type) {
        Converter<?> conv = converters.get(type);
        if (conv == null) {
            throw new IllegalArgumentException(
                    "No Converter registered for " + type.getName()
                            + " — Ravel M1 only supports String. M2 will add built-in"
                            + " and implicit converters.");
        }
        return (Converter<T>) conv;
    }
}
