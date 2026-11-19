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
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.spi.AfterDeploymentValidation;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.enterprise.inject.spi.EventContext;
import jakarta.enterprise.inject.spi.EventMetadata;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.ObserverMethod;
import jakarta.enterprise.inject.spi.ProcessAnnotatedType;
import jakarta.enterprise.inject.spi.ProcessSyntheticObserverMethod;
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
 *       that all required properties are available in current {@link Config};</li>
 *   <li>runs at {@link AfterDeploymentValidation} the check of the configuration that
 *       {@link ConfigCdiExtension} registered for the start of the application.</li>
 * </ol>
 *
 * <p>Only a full CDI container runs a portable extension, and it does so while it deploys the
 * application: here "deployment" and "start" read the same configuration.</p>
 */
public class ConfigPropertiesExclusionExtension implements Extension {

    private final List<Class<?>> configPropertiesClasses = new ArrayList<>();

    /** The start checks of {@link ConfigCdiExtension}, taken over to run at deployment validation. */
    private final List<ObserverMethod<Startup>> startChecks = new ArrayList<>();

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
            validateConfigPropertiesClass(beanClass, config, event);
        }
    }

    /**
     * Takes over the start check {@link ConfigCdiExtension} registered as a synthetic observer of
     * {@link Startup} — its bean class is the extension that registered it — so that
     * {@link #runStartChecks} runs it at deployment validation, and the container does not run it
     * again at {@code Startup}.
     *
     * <p>A full CDI container runs the build compatible extension while it deploys the application:
     * the configuration it reads then is the one the application starts with, a problem with it is a
     * deployment problem (MP Config 3.1 §6.1), and not every such container fires {@code Startup}
     * (the embedded Weld of Arquillian, which runs the TCK, does not). On a CDI Lite container this
     * extension never runs, and the check stays where the build compatible extension put it
     * (ravel#50).</p>
     */
    public <X> void moveStartCheckToDeploymentValidation(
            @Observes ProcessSyntheticObserverMethod<Startup, X> event) {
        ObserverMethod<Startup> observer = event.getObserverMethod();
        if (observer.getBeanClass() == ConfigCdiExtension.class) {
            startChecks.add(observer);
            event.veto();
        }
    }

    /**
     * Runs the start checks at deployment validation: the {@code DeploymentException} a check
     * throws, which lists every problem of the configuration, is a deployment problem.
     */
    public void runStartChecks(@Observes AfterDeploymentValidation event) {
        for (ObserverMethod<Startup> startCheck : startChecks) {
            try {
                startCheck.notify(new DeploymentValidation(new Startup()));
            } catch (RuntimeException failure) {
                event.addDeploymentProblem(deploymentProblemIn(failure));
            }
        }
    }

    /**
     * The {@code DeploymentException} of the start check, which the container may have wrapped
     * (Weld notifies a synthetic observer through a callback, and throws an
     * {@code ObserverException}); the failure itself when it carries none.
     */
    private static Throwable deploymentProblemIn(RuntimeException failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof DeploymentException) {
                return current;
            }
        }
        return failure;
    }

    /** The event a start check is notified with at deployment validation; it reads neither. */
    private record DeploymentValidation(Startup startup) implements EventContext<Startup> {
        @Override
        public Startup getEvent() {
            return startup;
        }

        @Override
        public EventMetadata getMetadata() {
            return null;
        }
    }

    private static void validateConfigPropertiesClass(
            Class<?> beanClass, Config config, AfterDeploymentValidation event) {
        ConfigProperties anno = beanClass.getAnnotation(ConfigProperties.class);
        String prefix = anno != null && !ConfigProperties.UNCONFIGURED_PREFIX.equals(anno.prefix())
                ? anno.prefix() : "";
        Object probe = tryInstantiate(beanClass);
        for (Field field : beanClass.getDeclaredFields()) {
            ConfigProperty fieldAnno = field.getAnnotation(ConfigProperty.class);
            if (isRequired(field, fieldAnno, probe)) {
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

    /**
     * A field is required unless it is static, has an {@code Optional}-like type, has a
     * configured {@code defaultValue}, or carries a Java initializer.
     */
    private static boolean isRequired(Field field, ConfigProperty fieldAnno, Object probe) {
        if (Modifier.isStatic(field.getModifiers()) || isOptional(field.getType())) {
            return false;
        }
        if (fieldAnno != null && !ConfigProperty.UNCONFIGURED_VALUE.equals(fieldAnno.defaultValue())) {
            return false;
        }
        return !hasInitializer(probe, field);
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
        } catch (Exception _) {
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
        } catch (IllegalAccessException _) {
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
