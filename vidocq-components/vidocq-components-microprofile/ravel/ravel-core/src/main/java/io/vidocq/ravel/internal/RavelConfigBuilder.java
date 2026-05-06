/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.internal;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigBuilder;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSourceProvider;
import org.eclipse.microprofile.config.spi.Converter;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Implémentation MP Config 3.1 §3 — builder fluent pour produire un {@link RavelConfig}.
 *
 * <p>Comportement par défaut : aucun source enregistré, mais le converter built-in
 * {@code String → identity} est pré-installé. Appeler {@link #addDefaultSources()},
 * {@link #addDiscoveredSources()}, {@link #addDiscoveredConverters()} active les
 * options canoniques.</p>
 *
 * <p><b>Note M1</b> : la priorité des converters n'est pas honorée — un converter
 * écrase simplement le précédent enregistré pour le même type. M2 implémentera
 * la priorité {@code @Priority} et les built-in pour les types primitifs / temporels.</p>
 */
public final class RavelConfigBuilder implements ConfigBuilder {

    private final List<ConfigSource> sources = new ArrayList<>();
    private final Map<Class<?>, Converter<?>> converters = new HashMap<>();
    private ClassLoader classLoader;

    public RavelConfigBuilder() {
        // §5.1 — String built-in converter (identité). M2 ajoutera les autres built-in.
        converters.put(String.class, IdentityStringConverter.INSTANCE);
        this.classLoader = Thread.currentThread().getContextClassLoader();
    }

    @Override
    public ConfigBuilder addDefaultSources() {
        // §3.4 — 3 sources canoniques.
        sources.add(new SystemPropertiesConfigSource());
        sources.add(new EnvironmentVariablesConfigSource());
        sources.addAll(MicroprofilePropertiesConfigSource.loadAll(resolveClassLoader()));
        return this;
    }

    @Override
    public ConfigBuilder addDiscoveredSources() {
        // §3.5 — ServiceLoader sur ConfigSource ET ConfigSourceProvider.
        ClassLoader cl = resolveClassLoader();
        ServiceLoader.load(ConfigSource.class, cl).forEach(sources::add);
        ServiceLoader.load(ConfigSourceProvider.class, cl)
                .forEach(p -> p.getConfigSources(cl).forEach(sources::add));
        return this;
    }

    @Override
    public ConfigBuilder addDiscoveredConverters() {
        // §5.3 — ServiceLoader sur Converter. M1 : on charge mais l'inférence du
        // type cible nécessite l'introspection du type paramétrique générique.
        ServiceLoader.load(Converter.class, resolveClassLoader())
                .forEach(this::registerConverterByGenericType);
        return this;
    }

    @Override
    public ConfigBuilder forClassLoader(ClassLoader loader) {
        this.classLoader = loader;
        return this;
    }

    @Override
    public ConfigBuilder withSources(ConfigSource... configSources) {
        Objects.requireNonNull(configSources, "configSources");
        for (ConfigSource s : configSources) {
            sources.add(Objects.requireNonNull(s, "configSource"));
        }
        return this;
    }

    @Override
    public ConfigBuilder withConverters(Converter<?>... converters) {
        Objects.requireNonNull(converters, "converters");
        for (Converter<?> c : converters) {
            registerConverterByGenericType(Objects.requireNonNull(c, "converter"));
        }
        return this;
    }

    @Override
    public <T> ConfigBuilder withConverter(Class<T> type, int priority, Converter<T> converter) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(converter, "converter");
        // M1 : priority ignorée — le dernier converter enregistré gagne.
        // M2 implémentera @Priority + comparaison.
        converters.put(type, converter);
        return this;
    }

    @Override
    public Config build() {
        return new RavelConfig(List.copyOf(sources), Map.copyOf(converters), resolveClassLoader());
    }

    // -------- internals --------

    private ClassLoader resolveClassLoader() {
        return classLoader != null
                ? classLoader
                : Thread.currentThread().getContextClassLoader();
    }

    /**
     * Enregistre un converter en inférant son type cible via le paramètre générique
     * de l'interface {@code Converter<T>}. Best effort — si l'inférence échoue
     * (converter générique brut), le converter est ignoré silencieusement.
     */
    private void registerConverterByGenericType(Converter<?> converter) {
        Class<?> target = inferConverterTargetType(converter.getClass());
        if (target != null) {
            converters.put(target, converter);
        }
    }

    private static Class<?> inferConverterTargetType(Class<?> converterClass) {
        // Cherche dans la chaîne d'interfaces le Converter<T> et extrait T.
        for (Type genericInterface : converterClass.getGenericInterfaces()) {
            if (genericInterface instanceof ParameterizedType pt
                    && pt.getRawType() == Converter.class
                    && pt.getActualTypeArguments().length == 1
                    && pt.getActualTypeArguments()[0] instanceof Class<?> targetClass) {
                return targetClass;
            }
        }
        // Remonte vers la superclasse : un Converter peut hériter d'une classe
        // qui implémente déjà Converter<T>.
        Class<?> superClass = converterClass.getSuperclass();
        if (superClass != null && superClass != Object.class) {
            return inferConverterTargetType(superClass);
        }
        return null;
    }
}
