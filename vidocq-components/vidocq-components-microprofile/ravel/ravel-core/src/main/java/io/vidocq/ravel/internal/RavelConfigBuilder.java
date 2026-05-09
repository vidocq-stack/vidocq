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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Implémentation MP Config 3.1 §3 — builder fluent pour produire un {@link RavelConfig}.
 *
 * <p>Comportement par défaut : tous les converters built-in (§5.1, §5.2 types
 * automatiques scalaires) sont pré-enregistrés à la <b>priorité 1</b> ; tout converter
 * applicatif (priorité par défaut 100, §5.3) les écrase automatiquement.</p>
 *
 * <p>Ordre de précédence des converters (§5.3) :</p>
 * <ol>
 *   <li>celui avec la <b>plus haute</b> priorité {@code @jakarta.annotation.Priority}</li>
 *   <li>en cas d'égalité, le dernier enregistré gagne (LIFO d'écriture).</li>
 * </ol>
 */
public final class RavelConfigBuilder implements ConfigBuilder {

    private final List<ConfigSource> sources = new ArrayList<>();
    private final Map<Class<?>, PrioritizedConverter> converters = new LinkedHashMap<>();
    private ClassLoader classLoader;

    public RavelConfigBuilder() {
        // §5.1 / §5.2 — pré-enregistrement des built-in à la priorité 1.
        for (Map.Entry<Class<?>, Converter<?>> e : BuiltInConverters.all().entrySet()) {
            converters.put(e.getKey(),
                    new PrioritizedConverter(BuiltInConverters.BUILT_IN_PRIORITY, e.getValue()));
        }
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
        // §5.3 — ServiceLoader sur Converter + lecture @Priority (def. 100).
        ServiceLoader.load(Converter.class, resolveClassLoader())
                .forEach(c -> registerConverter(c, PrioritizedConverter.readPriority(c)));
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
            Objects.requireNonNull(c, "converter");
            registerConverter(c, PrioritizedConverter.readPriority(c));
        }
        return this;
    }

    @Override
    public <T> ConfigBuilder withConverter(Class<T> type, int priority, Converter<T> converter) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(converter, "converter");
        // La priorité est explicite : on l'utilise telle quelle.
        registerConverter(type, priority, converter);
        return this;
    }

    @Override
    public Config build() {
        // Aplatir Map<Class<?>, PrioritizedConverter> → Map<Class<?>, Converter<?>>
        Map<Class<?>, Converter<?>> resolved = new HashMap<>(converters.size());
        for (var e : converters.entrySet()) {
            resolved.put(e.getKey(), e.getValue().converter());
        }

        List<ConfigSource> effectiveSources = List.copyOf(sources);
        String profile = trimToNull(lookupRaw(effectiveSources, "mp.config.profile"));
        if (profile != null) {
            // §7.5 — charge en plus les fichiers profil-aware
            // {@code META-INF/microprofile-config-{profile}.properties} (ordinal 110).
            var withProfile = new ArrayList<>(effectiveSources);
            withProfile.addAll(MicroprofilePropertiesConfigSource.loadProfile(resolveClassLoader(), profile));
            // §7.5 — wrapper profiled en ordinal +1 pour masquer la clé non profilée.
            var profiled = new ArrayList<ConfigSource>(withProfile.size() * 2);
            for (ConfigSource s : withProfile) {
                profiled.add(new ProfiledConfigSource(s, profile));
                profiled.add(s);
            }
            effectiveSources = List.copyOf(profiled);
        }

        boolean expressionsEnabled = true;
        String expressionFlag = trimToNull(lookupRaw(effectiveSources, "mp.config.property.expressions.enabled"));
        if (expressionFlag != null && expressionFlag.equalsIgnoreCase("false")) {
            expressionsEnabled = false;
        }

        return new RavelConfig(
                List.copyOf(effectiveSources),
                Map.copyOf(resolved),
                resolveClassLoader(),
                expressionsEnabled);
    }

    // -------- internals --------

    private ClassLoader resolveClassLoader() {
        return classLoader != null
                ? classLoader
                : Thread.currentThread().getContextClassLoader();
    }

    private void registerConverter(Converter<?> converter, int priority) {
        Class<?> target = inferConverterTargetType(converter.getClass());
        if (target != null) {
            registerConverter(target, priority, converter);
        }
        // Si l'inférence échoue (converter générique brut), on ignore silencieusement.
    }

    private <T> void registerConverter(Class<T> type, int priority, Converter<?> converter) {
        registerConverterEntry(type, priority, converter);
        // §5.1 — un converter applicatif sur le type boxé (ex. Integer) doit
        // également écraser le built-in primitif (int) afin que
        // {@code config.getValue("k", int.class)} respecte la priorité utilisateur.
        Class<?> twin = primitiveBoxingTwin(type);
        if (twin != null) {
            registerConverterEntry(twin, priority, converter);
        }
    }

    private void registerConverterEntry(Class<?> type, int priority, Converter<?> converter) {
        PrioritizedConverter existing = converters.get(type);
        if (existing == null || priority >= existing.priority()) {
            converters.put(type, new PrioritizedConverter(priority, converter));
        }
    }

    private static Class<?> primitiveBoxingTwin(Class<?> type) {
        if (type == Boolean.class) return boolean.class;
        if (type == Byte.class) return byte.class;
        if (type == Short.class) return short.class;
        if (type == Integer.class) return int.class;
        if (type == Long.class) return long.class;
        if (type == Float.class) return float.class;
        if (type == Double.class) return double.class;
        if (type == Character.class) return char.class;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return null;
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

    private static String lookupRaw(List<ConfigSource> sourceList, String key) {
        var sorted = new ArrayList<>(sourceList);
        sorted.sort(java.util.Comparator.comparingInt(ConfigSource::getOrdinal).reversed());
        for (ConfigSource s : sorted) {
            String value = s.getValue(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
