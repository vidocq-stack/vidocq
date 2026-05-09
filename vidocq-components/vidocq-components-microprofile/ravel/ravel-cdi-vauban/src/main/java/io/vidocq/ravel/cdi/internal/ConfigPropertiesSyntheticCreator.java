/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.cdi.internal;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.spi.InjectionPoint;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperties;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Creator pour les synthetic beans {@code @ConfigProperties} (spec MP Config 3.1 §6.4).
 *
 * <p>Le préfixe effectif est résolu depuis le point d'injection courant :
 * <ol>
 *   <li>préfixe {@code @ConfigProperties} sur l'IP — sauf si {@link ConfigProperties#UNCONFIGURED_PREFIX}</li>
 *   <li>préfixe {@code @ConfigProperties} de classe sur le bean — sauf si {@code UNCONFIGURED_PREFIX}</li>
 *   <li>chaîne vide ⇒ pas de préfixe</li>
 * </ol>
 * Pour chaque champ non-static, on lookup {@code <prefix>.<fieldName>} (ou
 * {@code @ConfigProperty.name()} si défini) et on assigne la valeur convertie.
 */
public class ConfigPropertiesSyntheticCreator implements SyntheticBeanCreator<Object> {

    @Override
    public Object create(Instance<Object> lookup, Parameters params) {
        InjectionPoint ip = lookup.select(InjectionPoint.class).get();
        Class<?> beanClass = resolveBeanClass(ip.getType());
        String fieldPrefix = resolveFieldPrefix(ip);
        String resolvedPrefix = resolvePrefix(beanClass, fieldPrefix);

        Object instance = newInstance(beanClass);
        Config config = ConfigProvider.getConfig();
        for (Field field : beanClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            populateField(instance, field, resolvedPrefix, config);
        }
        return instance;
    }

    private static Class<?> resolveBeanClass(java.lang.reflect.Type ipType) {
        if (ipType instanceof Class<?> c) return c;
        if (ipType instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> c) return c;
        throw new IllegalArgumentException("Unsupported @ConfigProperties type: " + ipType);
    }

    private static String resolveFieldPrefix(InjectionPoint ip) {
        // Cas 1 : lookup CDI standard (champ annoté) — l'AnnotatedField expose
        // l'annotation @ConfigProperties.
        var annotated = ip.getAnnotated();
        if (annotated != null) {
            ConfigProperties direct = annotated.getAnnotation(ConfigProperties.class);
            if (direct != null) {
                return direct.prefix();
            }
        }
        // Cas 2 : lookup programmatique via {@code CDI.current().select(BeanX.class,
        // ConfigProperties.Literal.of("foo"))} — getAnnotated() peut être null,
        // l'annotation est passée comme qualifiant au lieu d'être attachée à un membre.
        for (var qualifier : ip.getQualifiers()) {
            if (qualifier instanceof ConfigProperties cp) {
                return cp.prefix();
            }
        }
        return ConfigProperties.UNCONFIGURED_PREFIX;
    }

    private static String resolvePrefix(Class<?> beanClass, String fieldPrefix) {
        if (!ConfigProperties.UNCONFIGURED_PREFIX.equals(fieldPrefix)) {
            return fieldPrefix;
        }
        ConfigProperties classAnno = beanClass.getAnnotation(ConfigProperties.class);
        if (classAnno != null && !ConfigProperties.UNCONFIGURED_PREFIX.equals(classAnno.prefix())) {
            return classAnno.prefix();
        }
        return "";
    }

    private static Object newInstance(Class<?> beanClass) {
        try {
            var ctor = beanClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Cannot instantiate @ConfigProperties bean " + beanClass.getName(), e);
        }
    }

    private static void populateField(Object instance, Field field, String prefix, Config config) {
        ConfigProperty fieldAnno = field.getAnnotation(ConfigProperty.class);
        String propertyName = propertyName(field, fieldAnno, prefix);
        Class<?> rawType = field.getType();
        java.lang.reflect.Type genericType = field.getGenericType();

        Object value = lookupValue(config, propertyName, rawType, genericType, fieldAnno);
        if (value == null) return;

        try {
            field.setAccessible(true);
            field.set(instance, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "Cannot set field " + field.getDeclaringClass().getName() + "." + field.getName(), e);
        }
    }

    private static String propertyName(Field field, ConfigProperty fieldAnno, String prefix) {
        if (fieldAnno != null
                && !ConfigProperty.UNCONFIGURED_VALUE.equals(fieldAnno.name())
                && !fieldAnno.name().isEmpty()) {
            return prefix.isEmpty() ? fieldAnno.name() : prefix + "." + fieldAnno.name();
        }
        return prefix.isEmpty() ? field.getName() : prefix + "." + field.getName();
    }

    private static Object lookupValue(Config config, String name, Class<?> rawType,
                                      java.lang.reflect.Type genericType, ConfigProperty fieldAnno) {
        if (rawType == Optional.class) {
            Class<?> wrapped = optionalArg(genericType);
            return config.getOptionalValue(name, wrapped);
        }
        if (rawType == OptionalInt.class) {
            return config.getOptionalValue(name, Integer.class)
                    .map(v -> (Object) OptionalInt.of((Integer) v))
                    .orElse(OptionalInt.empty());
        }
        if (rawType == OptionalLong.class) {
            return config.getOptionalValue(name, Long.class)
                    .map(v -> (Object) OptionalLong.of((Long) v))
                    .orElse(OptionalLong.empty());
        }
        if (rawType == OptionalDouble.class) {
            return config.getOptionalValue(name, Double.class)
                    .map(v -> (Object) OptionalDouble.of((Double) v))
                    .orElse(OptionalDouble.empty());
        }
        Optional<?> v = config.getOptionalValue(name, rawType);
        if (v.isPresent()) return v.get();
        if (fieldAnno != null && !ConfigProperty.UNCONFIGURED_VALUE.equals(fieldAnno.defaultValue())) {
            return config.getConverter(rawType)
                    .map(c -> (Object) c.convert(fieldAnno.defaultValue()))
                    .orElse(null);
        }
        return null;
    }

    private static Class<?> optionalArg(java.lang.reflect.Type genericType) {
        if (genericType instanceof ParameterizedType pt && pt.getActualTypeArguments().length == 1
                && pt.getActualTypeArguments()[0] instanceof Class<?> c) {
            return c;
        }
        return String.class;
    }
}
