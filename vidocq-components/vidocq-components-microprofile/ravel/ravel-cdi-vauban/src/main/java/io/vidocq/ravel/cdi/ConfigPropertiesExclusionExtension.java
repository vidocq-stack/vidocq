/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
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
 * Portable CDI extension that:
 * <ol>
 *   <li>vetoes type-level {@code @ConfigProperties} beans so BCE can synthesize them;</li>
 *   <li>collects those classes and validates at {@link AfterDeploymentValidation}
 *       that all required properties are available in current {@link Config}.</li>
 * </ol>
 */
public class ConfigPropertiesExclusionExtension implements Extension {

    private final List<Class<?>> configPropertiesClasses = new ArrayList<>();

    /**
     * Vetoes type-level {@code @ConfigProperties} classes to let BCE synthesize them.
     * Does not veto classes using {@code @ConfigProperties} only as an IP qualifier.
     */
    public <T> void vetoConfigPropertiesBeans(
            @Observes @WithAnnotations(ConfigProperties.class) ProcessAnnotatedType<T> event) {
        if (event.getAnnotatedType().isAnnotationPresent(ConfigProperties.class)) {
            configPropertiesClasses.add(event.getAnnotatedType().getJavaClass());
            event.veto();
        }
    }

    /**
     * Validates deployed {@code @ConfigProperties} classes: each required field
     * must have a value source (config property, defaultValue, Optional type,
     * or Java initializer).
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
