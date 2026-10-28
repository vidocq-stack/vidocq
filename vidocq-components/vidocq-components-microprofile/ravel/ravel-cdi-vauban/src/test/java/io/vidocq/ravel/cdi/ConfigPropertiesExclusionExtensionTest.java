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

import jakarta.enterprise.inject.spi.AfterDeploymentValidation;
import jakarta.enterprise.inject.spi.AnnotatedType;
import jakarta.enterprise.inject.spi.ProcessAnnotatedType;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperties;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ConfigPropertiesExclusionExtension — portable veto and §6.4 validation")
class ConfigPropertiesExclusionExtensionTest {

    private static final String HOST_KEY = "complete.host";

    @AfterEach
    void cleanup() {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        Config cfg = resolver.getConfig(getClass().getClassLoader());
        resolver.releaseConfig(cfg);
    }

    @Test
    void type_level_ConfigProperties_class_is_vetoed() {
        AtomicBoolean vetoed = new AtomicBoolean();

        new ConfigPropertiesExclusionExtension().vetoConfigPropertiesBeans(processAnnotatedType(Complete.class, vetoed));

        assertTrue(vetoed.get());
    }

    @Test
    void class_using_ConfigProperties_only_on_an_injection_point_is_kept() {
        AtomicBoolean vetoed = new AtomicBoolean();

        new ConfigPropertiesExclusionExtension().vetoConfigPropertiesBeans(processAnnotatedType(Consumer.class, vetoed));

        assertFalse(vetoed.get());
    }

    @Test
    void fully_configured_class_passes_validation() {
        registerConfig(Map.of(HOST_KEY, "localhost", "complete.address", "10.0.0.1"));

        assertEquals(List.of(), problemsFor(Complete.class));
    }

    @Test
    void missing_required_member_is_reported_with_its_prefixed_name() {
        registerConfig(Map.of(HOST_KEY, "localhost"));

        List<Throwable> problems = problemsFor(Complete.class);

        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().getMessage().contains("'complete.address'"), problems.getFirst().getMessage());
    }

    @Test
    void empty_value_counts_as_missing() {
        registerConfig(Map.of(HOST_KEY, "", "complete.address", "10.0.0.1"));

        List<Throwable> problems = problemsFor(Complete.class);

        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().getMessage().contains("'complete.host'"), problems.getFirst().getMessage());
    }

    @Test
    void every_zero_valued_primitive_without_value_is_required() {
        registerConfig(Map.of());

        List<String> messages = problemsFor(Primitives.class).stream().map(Throwable::getMessage).toList();

        assertEquals(8, messages.size());
        for (String member : List.of("flag", "b", "s", "i", "l", "f", "d", "c")) {
            assertTrue(messages.stream().anyMatch(m -> m.contains("'" + member + "'")), member);
        }
    }

    @Test
    void class_without_no_arg_constructor_cannot_use_java_initializers() {
        registerConfig(Map.of());

        List<Throwable> problems = problemsFor(NoDefaultConstructor.class);

        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().getMessage().contains("'ctor.port'"), problems.getFirst().getMessage());
    }

    // -- Helpers -------------------------------------------------------------------------------

    private static List<Throwable> problemsFor(Class<?> beanClass) {
        ConfigPropertiesExclusionExtension extension = new ConfigPropertiesExclusionExtension();
        extension.vetoConfigPropertiesBeans(processAnnotatedType(beanClass, new AtomicBoolean()));
        List<Throwable> problems = new ArrayList<>();
        extension.validateConfigProperties((AfterDeploymentValidation) Proxy.newProxyInstance(
                AfterDeploymentValidation.class.getClassLoader(), new Class<?>[]{AfterDeploymentValidation.class},
                (_, method, args) -> {
                    if ("addDeploymentProblem".equals(method.getName())) {
                        problems.add((Throwable) args[0]);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }));
        return problems;
    }

    @SuppressWarnings("unchecked")
    private static <T> ProcessAnnotatedType<T> processAnnotatedType(Class<T> javaClass, AtomicBoolean vetoed) {
        AnnotatedType<T> type = (AnnotatedType<T>) Proxy.newProxyInstance(
                AnnotatedType.class.getClassLoader(), new Class<?>[]{AnnotatedType.class},
                (_, method, args) -> switch (method.getName()) {
                    case "getJavaClass" -> javaClass;
                    case "isAnnotationPresent" -> javaClass.isAnnotationPresent(
                            (Class<? extends java.lang.annotation.Annotation>) args[0]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (ProcessAnnotatedType<T>) Proxy.newProxyInstance(
                ProcessAnnotatedType.class.getClassLoader(), new Class<?>[]{ProcessAnnotatedType.class},
                (_, method, _) -> switch (method.getName()) {
                    case "getAnnotatedType" -> type;
                    case "veto" -> {
                        vetoed.set(true);
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private void registerConfig(Map<String, String> values) {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        Config custom = resolver.getBuilder()
                .withSources(new TestSource(values))
                .forClassLoader(getClass().getClassLoader())
                .build();
        resolver.registerConfig(custom, getClass().getClassLoader());
    }

    private record TestSource(Map<String, String> values) implements ConfigSource {
        @Override public Map<String, String> getProperties() { return values; }
        @Override public Set<String> getPropertyNames() { return values.keySet(); }
        @Override public String getValue(String propertyName) { return values.get(propertyName); }
        @Override public String getName() { return "test-source"; }
        @Override public int getOrdinal() { return 1000; }
    }

    @SuppressWarnings("unused")
    @ConfigProperties(prefix = "complete")
    static class Complete {
        static final String CONSTANT = "ignored";
        String host;
        @ConfigProperty(name = "address")
        String ip;
        @ConfigProperty(defaultValue = "80")
        int port;
        int timeout = 30;
        Optional<String> description;
        OptionalInt retries;
        OptionalLong size;
        OptionalDouble ratio;
    }

    @SuppressWarnings("unused")
    @ConfigProperties
    static class Primitives {
        boolean flag;
        byte b;
        short s;
        int i;
        long l;
        float f;
        double d;
        char c;
    }

    @SuppressWarnings("unused")
    @ConfigProperties(prefix = "ctor")
    static class NoDefaultConstructor {
        int port = 8080;

        NoDefaultConstructor(int port) {
            this.port = port;
        }
    }

    @SuppressWarnings("unused")
    static class Consumer {
        @ConfigProperties
        Complete complete;
    }
}
