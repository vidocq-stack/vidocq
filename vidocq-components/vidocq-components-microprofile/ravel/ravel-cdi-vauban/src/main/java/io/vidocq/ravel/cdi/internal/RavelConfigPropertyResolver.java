/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.cdi.internal;

import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.inject.Provider;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.lang.reflect.Member;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Resolution logic for a {@code @ConfigProperty} injection point.
 *
 * <p>Shared by the legacy CDI producer {@code RavelConfigProducer} (used by
 * non-CDI unit tests) and the BCE synthetic creator (used by Weld at TCK runtime).</p>
 *
 * <p>MicroProfile Config 3.1 §6.1: a CDI container must support injection of
 * any type for which a {@code Converter} exists.</p>
 */
public final class RavelConfigPropertyResolver {

    private RavelConfigPropertyResolver() {
    }

    /**
     * Resolves the value for a {@code @ConfigProperty} injection point.
     *
     * @param injectionPoint current CDI-provided injection point
     * @return converted value ({@code String}, scalar, {@code Optional<T>},
     *         {@code Provider<T>}, or {@code Supplier<T>})
     */
    public static Object resolve(InjectionPoint injectionPoint) {
        ConfigProperty metadata = injectionPoint.getAnnotated().getAnnotation(ConfigProperty.class);
        if (metadata == null) {
            throw new IllegalArgumentException("@ConfigProperty metadata is required on injection point");
        }
        Member member = injectionPoint.getMember();
        String key = resolvePropertyName(metadata, member);
        Type targetType = injectionPoint.getType();

        if (isOptional(targetType)) {
            Class<?> wrapped = wrappedType(targetType);
            return resolveOptional(key, wrapped, metadata.defaultValue());
        }
        if (isProvider(targetType)) {
            Class<?> wrapped = wrappedType(targetType);
            return (Provider<?>) () -> resolveRequired(key, wrapped, metadata.defaultValue());
        }
        if (isSupplier(targetType)) {
            Class<?> wrapped = wrappedType(targetType);
            return (Supplier<?>) () -> resolveRequired(key, wrapped, metadata.defaultValue());
        }
        // Spec §5.4: List<T>, Set<T> use comma split + element-wise conversion.
        if (isList(targetType) || isSet(targetType)) {
            Class<?> element = wrappedType(targetType);
            return resolveCollection(key, element, isSet(targetType), metadata.defaultValue());
        }
        Class<?> clazz = rawClass(targetType);
        // Spec §6.3: direct ConfigValue injection, no Converter involved.
        if (clazz == ConfigValue.class) {
            ConfigValue cv = ConfigProvider.getConfig().getConfigValue(key);
            if (cv != null && cv.getValue() != null) {
                return cv;
            }
            // Missing property: apply defaultValue in a synthetic ConfigValue instance.
            if (!ConfigProperty.UNCONFIGURED_VALUE.equals(metadata.defaultValue())) {
                return new DefaultedConfigValue(key, metadata.defaultValue());
            }
            return cv != null ? cv : new DefaultedConfigValue(key, null);
        }
        // OptionalInt/Long/Double: built-in converter already returns empty when
        // the property is missing, so no deployment error.
        if (clazz == OptionalInt.class || clazz == OptionalLong.class || clazz == OptionalDouble.class) {
            return resolvePrimitiveOptional(key, clazz, metadata.defaultValue());
        }
        return resolveRequired(key, clazz, metadata.defaultValue());
    }

    private static Object resolvePrimitiveOptional(String key, Class<?> type, String defaultValue) {
        Config config = ConfigProvider.getConfig();
        if (rawValuePresent(config, key)) {
            // §5.3: property is present; return conversion result or primitive empty,
            // without applying defaultValue.
            Optional<?> value = config.getOptionalValue(key, type);
            if (value.isPresent()) {
                return value.get();
            }
            return primitiveEmpty(type);
        }
        if (!ConfigProperty.UNCONFIGURED_VALUE.equals(defaultValue)) {
            return convertDefault(config, type, defaultValue);
        }
        return primitiveEmpty(type);
    }

    private static Object primitiveEmpty(Class<?> type) {
        if (type == OptionalInt.class) return OptionalInt.empty();
        if (type == OptionalLong.class) return OptionalLong.empty();
        return OptionalDouble.empty();
    }

    private static Object resolveRequired(String key, Class<?> type, String defaultValue) {
        Config config = ConfigProvider.getConfig();
        if (rawValuePresent(config, key)) {
            Optional<?> value = config.getOptionalValue(key, type);
            if (value.isPresent()) {
                return value.get();
            }
            // §5.3: converter returned null for a present value -> deployment error
            // for required injection.
            throw new DeploymentException(
                    "Cannot convert config property '" + key + "' to " + type.getName()
                            + " (converter returned null)");
        }
        if (!ConfigProperty.UNCONFIGURED_VALUE.equals(defaultValue)) {
            if (defaultValue.isEmpty()) {
                throw new DeploymentException(
                        "Empty defaultValue is invalid for required config property '" + key + "'");
            }
            Object converted = convertDefault(config, type, defaultValue);
            if (converted != null) {
                return converted;
            }
            throw new DeploymentException(
                    "defaultValue for config property '" + key + "' converts to null");
        }
        throw new DeploymentException("Missing required config property '" + key + "' for type " + type.getName());
    }

    private static Optional<?> resolveOptional(String key, Class<?> wrappedType, String defaultValue) {
        Config config = ConfigProvider.getConfig();
        if (rawValuePresent(config, key)) {
            // §5.3: property is present; strict conversion, no default fallback.
            return config.getOptionalValue(key, wrappedType);
        }
        if (!ConfigProperty.UNCONFIGURED_VALUE.equals(defaultValue)) {
            return Optional.ofNullable(convertDefault(config, wrappedType, defaultValue));
        }
        return Optional.empty();
    }

    /**
     * §5.3: distinguishes "property found but converter returns null" from
     * "property absent from all sources".
     */
    private static boolean rawValuePresent(Config config, String key) {
        ConfigValue cv = config.getConfigValue(key);
        if (cv == null) return false;
        String resolved = cv.getValue();
        if (resolved == null) return false;
        return !resolved.isEmpty();
    }

    /**
     * Synthetic {@link ConfigValue} for {@code @ConfigProperty ConfigValue}
     * injections when property is missing.
     */
    private record DefaultedConfigValue(String name, String value) implements ConfigValue {
        @Override public String getName() { return name; }
        @Override public String getValue() { return value; }
        @Override public String getRawValue() { return value; }
        @Override public String getSourceName() { return null; }
        @Override public int getSourceOrdinal() { return 0; }
    }

    private static Object convertDefault(Config config, Class<?> type, String defaultValue) {
        return config.getConverter(type)
                .orElseThrow(() -> new DeploymentException("No converter for type " + type.getName()))
                .convert(defaultValue);
    }

    private static boolean isOptional(Type type) {
        return type instanceof ParameterizedType pt && pt.getRawType() == Optional.class;
    }

    private static boolean isProvider(Type type) {
        return type instanceof ParameterizedType pt && pt.getRawType() == Provider.class;
    }

    private static boolean isSupplier(Type type) {
        return type instanceof ParameterizedType pt && pt.getRawType() == Supplier.class;
    }

    private static boolean isList(Type type) {
        return type instanceof ParameterizedType pt && pt.getRawType() == List.class;
    }

    private static boolean isSet(Type type) {
        return type instanceof ParameterizedType pt && pt.getRawType() == Set.class;
    }

    private static Collection<Object> resolveCollection(
            String key, Class<?> elementType, boolean asSet, String defaultValue) {
        Config config = ConfigProvider.getConfig();
        String raw;
        if (rawValuePresent(config, key)) {
            ConfigValue cv = config.getConfigValue(key);
            raw = cv.getValue();
        } else if (!ConfigProperty.UNCONFIGURED_VALUE.equals(defaultValue)) {
            raw = defaultValue;
        } else {
            throw new DeploymentException("Missing required config property '" + key + "'");
        }
        // §5.4: comma split with backslash escaping, delegated through element
        // array conversion and then copied into the target collection.
        Object[] arr = (Object[]) config.getConverter(arrayClass(elementType))
                .orElseThrow(() -> new DeploymentException("No converter for " + elementType.getName() + "[]"))
                .convert(raw);
        if (arr == null) return asSet ? new LinkedHashSet<>() : new ArrayList<>();
        Collection<Object> out = asSet ? new LinkedHashSet<>(arr.length) : new ArrayList<>(arr.length);
        for (Object e : arr) out.add(e);
        return out;
    }

    private static Class<?> arrayClass(Class<?> componentType) {
        return java.lang.reflect.Array.newInstance(componentType, 0).getClass();
    }

    private static Class<?> wrappedType(Type type) {
        if (!(type instanceof ParameterizedType pt) || pt.getActualTypeArguments().length != 1) {
            throw new IllegalArgumentException("Expected single-parameterized type, got " + type.getTypeName());
        }
        return rawClass(pt.getActualTypeArguments()[0]);
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> c) {
            return c;
        }
        throw new IllegalArgumentException("Unsupported injection type: " + type.getTypeName());
    }

    private static String resolvePropertyName(ConfigProperty metadata, Member member) {
        String configured = metadata.name();
        if (configured != null
                && !configured.isBlank()
                && !ConfigProperty.UNCONFIGURED_VALUE.equals(configured)) {
            return configured;
        }
        // §6.1: default name is {@code <declaring-class-FQN>.<member-name>}.
        Class<?> declaring = member.getDeclaringClass();
        return declaring.getCanonicalName() + "." + member.getName();
    }
}

