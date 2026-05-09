/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.cdi;

import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.AfterDeploymentValidation;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.ProcessAnnotatedType;
import jakarta.enterprise.inject.spi.WithAnnotations;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.inject.ConfigProperties;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Extension portable (standard CDI) qui :
 * <ol>
 *   <li>veto les beans annotés {@code @ConfigProperties} au niveau classe pour
 *       laisser la BCE les synthétiser ;</li>
 *   <li>collecte ces classes pour valider, à {@link AfterDeploymentValidation},
 *       que toutes leurs propriétés requises sont disponibles dans la
 *       {@link Config} courante (cf.
 *       {@link org.eclipse.microprofile.config.tck.broken.ConfigPropertiesMissingPropertyInjectionTest}).</li>
 * </ol>
 */
public class ConfigPropertiesExclusionExtension implements Extension {

    private final List<Class<?>> configPropertiesClasses = new ArrayList<>();

    /**
     * Veto les classes annotées {@code @ConfigProperties} au niveau type pour
     * laisser la BCE les synthétiser. Ne veto pas les classes qui utilisent
     * {@code @ConfigProperties} uniquement comme qualifiant à un point d'injection
     * (ex. {@code InjectingBean} du TCK).
     */
    public <T> void vetoConfigPropertiesBeans(
            @Observes @WithAnnotations(ConfigProperties.class) ProcessAnnotatedType<T> event) {
        if (event.getAnnotatedType().isAnnotationPresent(ConfigProperties.class)) {
            configPropertiesClasses.add(event.getAnnotatedType().getJavaClass());
            event.veto();
        }
    }

    /**
     * Validation des classes {@code @ConfigProperties} déployées : pour chaque
     * classe collectée, on vérifie que tous les champs requis disposent d'une
     * source de valeur (propriété config, defaultValue, type Optional, ou
     * initialiseur Java).
     */
    public void validateConfigProperties(@Observes AfterDeploymentValidation event) {
        Config config = ConfigProvider.getConfig();
        for (Class<?> beanClass : configPropertiesClasses) {
            ConfigProperties anno = beanClass.getAnnotation(ConfigProperties.class);
            String prefix = anno != null && !ConfigProperties.UNCONFIGURED_PREFIX.equals(anno.prefix())
                    ? anno.prefix() : "";
            Object probe = tryInstantiate(beanClass);
            for (Field field : beanClass.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (isOptional(field.getType())) continue;
                ConfigProperty fieldAnno = field.getAnnotation(ConfigProperty.class);
                if (fieldAnno != null
                        && !ConfigProperty.UNCONFIGURED_VALUE.equals(fieldAnno.defaultValue())) continue;
                if (hasInitializer(probe, field)) continue;
                String name = propertyName(field, fieldAnno, prefix);
                ConfigValue cv = config.getConfigValue(name);
                String value = cv != null ? cv.getValue() : null;
                if (value == null || value.isEmpty()) {
                    event.addDeploymentProblem(new DeploymentException(
                            "@ConfigProperties: missing required property '" + name + "' for "
                                    + beanClass.getName() + "." + field.getName()));
                }
            }
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

    private static boolean isOptional(Class<?> type) {
        return type == Optional.class
                || type == OptionalInt.class
                || type == OptionalLong.class
                || type == OptionalDouble.class;
    }

    private static Object tryInstantiate(Class<?> beanClass) {
        try {
            var ctor = beanClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean hasInitializer(Object probe, Field field) {
        if (probe == null) return false;
        try {
            field.setAccessible(true);
            Object actual = field.get(probe);
            Object zero = zeroValue(field.getType());
            return !java.util.Objects.equals(actual, zero);
        } catch (IllegalAccessException e) {
            return false;
        }
    }

    private static Object zeroValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return (char) 0;
        return null;
    }
}
