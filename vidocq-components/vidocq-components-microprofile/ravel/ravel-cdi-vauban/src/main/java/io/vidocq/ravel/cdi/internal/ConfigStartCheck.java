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
package io.vidocq.ravel.cdi.internal;

import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserver;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.enterprise.inject.spi.EventContext;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.inject.ConfigProperties;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Checks, when the application starts, that the configuration it starts with satisfies its
 * {@code @ConfigProperty} and {@code @ConfigProperties} injection points (MicroProfile Config 3.1
 * §6.1 and §6.4), and fails the start with one {@link DeploymentException} listing every problem.
 *
 * <p>{@code ConfigCdiExtension} registers this class as a synthetic observer of {@link Startup}. The
 * extension reads no configuration: it runs where the application is built, and the configuration of
 * the build machine is not the one the application starts with. It records what must be checked as
 * plain text, in the parameters of the observer, which a container that runs its extensions at build
 * time only keeps with the observer; this class reads them back and needs nothing else of the
 * extension (ravel#50).
 *
 * <p>The parameters are parallel {@code String[]} arrays, one element per injection point:
 * <ul>
 *   <li>{@link #PROPERTY_KEYS}, {@link #PROPERTY_TYPES}, {@link #PROPERTY_DEFAULTS},
 *       {@link #PROPERTY_SITES} — the required {@code @ConfigProperty} injection points: the
 *       property key, the name of the type the value converts to (a class name, or a primitive
 *       type name), the {@code defaultValue} ({@link #NO_DEFAULT} when there is none), and where
 *       the value is injected;</li>
 *   <li>{@link #CLASS_NAMES}, {@link #CLASS_PREFIXES}, {@link #CLASS_SITES} — the
 *       {@code @ConfigProperties} classes: the class name, the prefix written at the injection
 *       point ({@link ConfigProperties#UNCONFIGURED_PREFIX} when the class decides), and where the
 *       class is injected (empty for a class that was only discovered).</li>
 * </ul>
 *
 * <p>Reflective by nature: the types are loaded by name, and the fields of a
 * {@code @ConfigProperties} class are read on the loaded class (see {@code internals.adoc},
 * "Generated code and reflective fallback").
 */
public final class ConfigStartCheck implements SyntheticObserver<Startup> {

    /** Parameter: the key of each required {@code @ConfigProperty} injection point. */
    public static final String PROPERTY_KEYS = "propertyKeys";
    /** Parameter: the name of the type each required property converts to. */
    public static final String PROPERTY_TYPES = "propertyTypes";
    /** Parameter: the {@code defaultValue} of each required property, or {@link #NO_DEFAULT}. */
    public static final String PROPERTY_DEFAULTS = "propertyDefaults";
    /** Parameter: where each required property is injected, as {@code Class#member}. */
    public static final String PROPERTY_SITES = "propertySites";
    /** Parameter: the name of each {@code @ConfigProperties} class. */
    public static final String CLASS_NAMES = "classNames";
    /** Parameter: the prefix written where each {@code @ConfigProperties} class is injected. */
    public static final String CLASS_PREFIXES = "classPrefixes";
    /** Parameter: where each {@code @ConfigProperties} class is injected; empty when only discovered. */
    public static final String CLASS_SITES = "classSites";

    /** The {@link #PROPERTY_DEFAULTS} element of an injection point without {@code defaultValue}. */
    public static final String NO_DEFAULT = ConfigProperty.UNCONFIGURED_VALUE;

    /**
     * The priority of the observer, {@code jakarta.interceptor.Interceptor.Priority.PLATFORM_BEFORE}:
     * before the {@code Startup} observers of the libraries and of the application, which may
     * already read the configuration.
     */
    public static final int PRIORITY = 0;

    private static final String[] NONE = new String[0];

    /**
     * @throws DeploymentException when the configuration misses a required value, or holds one
     *                             that does not convert; its message lists every problem
     */
    @Override
    public void observe(EventContext<Startup> event, Parameters params) {
        List<String> problems = problems(params, ConfigProvider.getConfig());
        if (!problems.isEmpty()) {
            throw new DeploymentException(describe(problems));
        }
    }

    /** Every problem {@code config} has with the injection points {@code params} describe. */
    static List<String> problems(Parameters params, Config config) {
        List<String> problems = new ArrayList<>();
        checkProperties(params, config, problems);
        checkClasses(params, config, problems);
        return problems;
    }

    private static String describe(List<String> problems) {
        StringBuilder message = new StringBuilder("Invalid MicroProfile Config deployment (")
                .append(problems.size())
                .append(problems.size() == 1 ? " problem):" : " problems):");
        for (String problem : problems) {
            message.append("\n - ").append(problem);
        }
        return message.toString();
    }

    private static String at(String site) {
        return site.isEmpty() ? "" : " [at " + site + "]";
    }

    // -- @ConfigProperty (§6.1) -------------------------------------------------------------------

    private static void checkProperties(Parameters params, Config config, List<String> problems) {
        String[] keys = params.get(PROPERTY_KEYS, String[].class, NONE);
        String[] types = params.get(PROPERTY_TYPES, String[].class, NONE);
        String[] defaults = params.get(PROPERTY_DEFAULTS, String[].class, NONE);
        String[] sites = params.get(PROPERTY_SITES, String[].class, NONE);
        for (int i = 0; i < keys.length; i++) {
            // A type this start cannot load is left to the injection itself.
            Class<?> targetType = loadType(types[i]);
            if (targetType != null) {
                String defaultValue = NO_DEFAULT.equals(defaults[i]) ? null : defaults[i];
                String problem = propertyProblem(config, keys[i], targetType, defaultValue);
                if (problem != null) {
                    problems.add(problem + at(sites[i]));
                }
            }
        }
    }

    /**
     * What keeps the required property {@code key} from being injected as a {@code targetType}, or
     * {@code null}: no value and no default, a value or a default that does not convert, an empty
     * default.
     */
    private static String propertyProblem(Config config, String key, Class<?> targetType, String defaultValue) {
        ConfigValue configValue = config.getConfigValue(key);
        String resolved = configValue != null ? configValue.getValue() : null;
        boolean rawPresent = resolved != null && !resolved.isEmpty();

        try {
            if (rawPresent) {
                return config.getOptionalValue(key, targetType).isEmpty()
                        ? "Cannot convert required config property '" + key + "' to " + targetType.getName()
                                + " (converter returned null)"
                        : null;
            }
            if (defaultValue == null) {
                return "Missing required config property '" + key + "' for type " + targetType.getName();
            }
            if (defaultValue.isEmpty()) {
                return "Empty defaultValue is invalid for required config property '" + key + "'";
            }
            Object convertedDefault = config.getConverter(targetType)
                    .orElseThrow(() -> new IllegalStateException("No converter for " + targetType.getName()))
                    .convert(defaultValue);
            return convertedDefault == null ? "defaultValue for config property '" + key + "' converts to null" : null;
        } catch (RuntimeException e) {
            return "Invalid @ConfigProperty for key '" + key + "': " + e.getMessage();
        }
    }

    // -- @ConfigProperties (§6.4) -----------------------------------------------------------------

    private static void checkClasses(Parameters params, Config config, List<String> problems) {
        String[] names = params.get(CLASS_NAMES, String[].class, NONE);
        String[] prefixes = params.get(CLASS_PREFIXES, String[].class, NONE);
        String[] sites = params.get(CLASS_SITES, String[].class, NONE);
        // The injection points come first: a class that was discovered and is injected under its
        // own prefix is reported once, at its injection point.
        Set<String> checked = new HashSet<>();
        for (int i = 0; i < names.length; i++) {
            // A class this start cannot load is left to the injection itself.
            Class<?> beanClass = loadClass(names[i]);
            if (beanClass != null) {
                String prefix = resolveConfigPropertiesPrefix(beanClass, prefixes[i]);
                boolean alreadyChecked = !checked.add(names[i] + '|' + prefix);
                if (!(alreadyChecked && sites[i].isEmpty())) {
                    checkFields(beanClass, prefix, sites[i], config, problems);
                }
            }
        }
    }

    /**
     * §6.4 — every property a {@code @ConfigProperties} class requires is present. A property is
     * required when its field has no fallback: no {@code @ConfigProperty(defaultValue=...)}, not an
     * {@code Optional} / {@code OptionalInt/Long/Double} type, no Java initializer (for instance
     * {@code int port = 9080;}, detected by instantiating the class and comparing the field to the
     * zero value of its type).
     */
    private static void checkFields(Class<?> beanClass, String resolvedPrefix, String site, Config config,
                                    List<String> problems) {
        Object probe = tryInstantiate(beanClass);
        for (Field field : beanClass.getDeclaredFields()) {
            ConfigProperty fieldAnno = field.getAnnotation(ConfigProperty.class);
            if (isRequiredField(field, fieldAnno, probe)) {
                String name = fieldConfigPropertyName(field, fieldAnno, resolvedPrefix);
                ConfigValue cv = config.getConfigValue(name);
                String resolved = cv != null ? cv.getValue() : null;
                if (resolved == null || resolved.isEmpty()) {
                    problems.add("@ConfigProperties: missing required property '" + name + "' for "
                            + beanClass.getName() + "." + field.getName() + at(site));
                }
            }
        }
    }

    /**
     * §6.4: a field is required unless it is static, has a configured {@code defaultValue}, has an
     * {@code Optional}-like type, or carries a Java initializer.
     */
    private static boolean isRequiredField(Field field, ConfigProperty fieldAnno, Object probe) {
        if (Modifier.isStatic(field.getModifiers())) {
            return false;
        }
        if (fieldAnno != null && !ConfigProperty.UNCONFIGURED_VALUE.equals(fieldAnno.defaultValue())) {
            return false;
        }
        return !isOptionalLike(field.getType()) && !hasJavaInitializer(probe, field);
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

    private static boolean hasJavaInitializer(Object probe, Field field) {
        if (probe == null) {
            return false;
        }
        try {
            field.setAccessible(true);
            return !Objects.equals(field.get(probe), zeroValue(field.getType()));
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

    /**
     * The prefix of a {@code @ConfigProperties} class: the one written at the injection point, else
     * the one of the class, else none (§6.4). {@code rawPrefix} is
     * {@link ConfigProperties#UNCONFIGURED_PREFIX} when the injection point writes none; an empty
     * prefix written there means "no prefix".
     */
    static String resolveConfigPropertiesPrefix(Class<?> beanClass, String rawPrefix) {
        if (!ConfigProperties.UNCONFIGURED_PREFIX.equals(rawPrefix)) {
            return rawPrefix;
        }
        ConfigProperties classAnno = beanClass.getAnnotation(ConfigProperties.class);
        if (classAnno != null && !ConfigProperties.UNCONFIGURED_PREFIX.equals(classAnno.prefix())) {
            return classAnno.prefix();
        }
        return "";
    }

    static String fieldConfigPropertyName(Field field, ConfigProperty anno, String resolvedPrefix) {
        if (anno != null && !ConfigProperty.UNCONFIGURED_VALUE.equals(anno.name()) && !anno.name().isEmpty()) {
            return resolvedPrefix.isEmpty() ? anno.name() : resolvedPrefix + "." + anno.name();
        }
        return resolvedPrefix.isEmpty() ? field.getName() : resolvedPrefix + "." + field.getName();
    }

    static boolean isOptionalLike(Class<?> rawType) {
        return rawType == Optional.class
                || rawType == OptionalInt.class
                || rawType == OptionalLong.class
                || rawType == OptionalDouble.class;
    }

    // -- Types, by name ---------------------------------------------------------------------------

    /** The primitive type or the class named {@code name}, or {@code null} when it cannot be loaded. */
    private static Class<?> loadType(String name) {
        Class<?> primitive = Class.forPrimitiveName(name);
        return primitive != null ? primitive : loadClass(name);
    }

    /** The thread context class loader first — it sees the application — then this class's own. */
    private static Class<?> loadClass(String name) {
        try {
            return Class.forName(name, false, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException _) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException _) {
                return null;
            }
        }
    }
}
