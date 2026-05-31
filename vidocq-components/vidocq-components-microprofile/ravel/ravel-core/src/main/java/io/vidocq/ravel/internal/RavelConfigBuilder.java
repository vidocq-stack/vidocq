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
 * MicroProfile Config 3.1 §3 implementation of a fluent builder creating
 * {@link RavelConfig} instances.
 *
 * <p>Default behavior: all built-in converters are pre-registered with
 * priority 1; application converters (default priority 100, §5.3) override
 * them automatically.</p>
 *
 * <p>Converter precedence (§5.3): highest priority wins; on ties, the last
 * registered converter wins.</p>
 */
public final class RavelConfigBuilder implements ConfigBuilder {

    private final List<ConfigSource> sources = new ArrayList<>();
    private final Map<Class<?>, PrioritizedConverter> converters = new LinkedHashMap<>();
    private ClassLoader classLoader;

    public RavelConfigBuilder() {
        // §5.1 / §5.2: pre-register built-ins with priority 1.
        for (Map.Entry<Class<?>, Converter<?>> e : BuiltInConverters.all().entrySet()) {
            converters.put(e.getKey(),
                    new PrioritizedConverter(BuiltInConverters.BUILT_IN_PRIORITY, e.getValue()));
        }
        this.classLoader = Thread.currentThread().getContextClassLoader();
    }

    @Override
    public ConfigBuilder addDefaultSources() {
        // §3.4: three standard sources.
        sources.add(new SystemPropertiesConfigSource());
        sources.add(new EnvironmentVariablesConfigSource());
        sources.addAll(MicroprofilePropertiesConfigSource.loadAll(resolveClassLoader()));
        return this;
    }

    @Override
    public ConfigBuilder addDiscoveredSources() {
        // §3.5: ServiceLoader over both ConfigSource and ConfigSourceProvider.
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
        // Explicit priority is used as provided.
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
            // §7.5: profiled wrapper uses ordinal +1 to shadow non-profiled keys.
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
        // If inference fails (raw generic converter), ignore silently.
    }

    private <T> void registerConverter(Class<T> type, int priority, Converter<?> converter) {
        registerConverterEntry(type, priority, converter);
        // §5.1: an application converter on boxed type (e.g., Integer) must also
        // override primitive built-in (int) so user priority is respected.
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
        // Walk the interface chain to find Converter<T> and extract T.
        for (Type genericInterface : converterClass.getGenericInterfaces()) {
            if (genericInterface instanceof ParameterizedType pt
                    && pt.getRawType() == Converter.class
                    && pt.getActualTypeArguments().length == 1
                    && pt.getActualTypeArguments()[0] instanceof Class<?> targetClass) {
                return targetClass;
            }
        }
        // Walk superclasses too: a converter may inherit Converter<T> from a base class.
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
