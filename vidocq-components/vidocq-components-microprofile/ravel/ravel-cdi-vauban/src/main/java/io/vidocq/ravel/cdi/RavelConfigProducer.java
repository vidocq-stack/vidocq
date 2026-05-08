/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.cdi;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.inject.Provider;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.lang.reflect.Member;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Producers CDI pour {@code Config} et {@code @ConfigProperty}.
 *
 * <p>Premier incrément M4 : support {@code Config}, scalaires, {@code Optional<T>},
 * {@code Provider<T>} et {@code Supplier<T>} avec fallback {@code defaultValue}.</p>
 */
@Dependent
public class RavelConfigProducer {

    @Produces
    @Dependent
    public Config produceConfig() {
        return ConfigProvider.getConfig();
    }

    @Produces
    @ConfigProperty
    @Dependent
    public Object produceConfigProperty(InjectionPoint injectionPoint) {
        ConfigProperty metadata = injectionPoint.getAnnotated().getAnnotation(ConfigProperty.class);
        if (metadata == null) {
            throw new IllegalArgumentException("@ConfigProperty metadata is required on injection point");
        }

        Member member = injectionPoint.getMember();
        String key = resolvePropertyName(metadata, member);
        Type targetType = injectionPoint.getType();

        if (isOptional(targetType)) {
            Class<?> wrapped = wrappedType(targetType);
            Optional<?> value = produceOptionalValue(key, wrapped, metadata.defaultValue());
            return value;
        }

        if (isProvider(targetType)) {
            Class<?> wrapped = wrappedType(targetType);
            return (Provider<?>) () -> produceRequiredValue(key, wrapped, metadata.defaultValue());
        }

        if (isSupplier(targetType)) {
            Class<?> wrapped = wrappedType(targetType);
            return (Supplier<?>) () -> produceRequiredValue(key, wrapped, metadata.defaultValue());
        }

        Class<?> clazz = rawClass(targetType);
        return produceRequiredValue(key, clazz, metadata.defaultValue());
    }

    private static Object produceRequiredValue(String key, Class<?> type, String defaultValue) {
        Config config = ConfigProvider.getConfig();
        Optional<?> value = config.getOptionalValue(key, type);
        if (value.isPresent()) {
            return value.get();
        }
        if (!ConfigProperty.UNCONFIGURED_VALUE.equals(defaultValue)) {
            return convertDefault(config, type, defaultValue);
        }
        throw new DeploymentException("Missing required config property '" + key + "' for type " + type.getName());
    }

    private static Optional<?> produceOptionalValue(String key, Class<?> wrappedType, String defaultValue) {
        Config config = ConfigProvider.getConfig();
        Optional<?> value = config.getOptionalValue(key, wrappedType);
        if (value.isPresent()) {
            return value;
        }
        if (!ConfigProperty.UNCONFIGURED_VALUE.equals(defaultValue)) {
            return Optional.ofNullable(convertDefault(config, wrappedType, defaultValue));
        }
        return Optional.empty();
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
            return metadata.name();
        }
        return member.getName();
    }
}


