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
package io.vidocq.runtime.extensions.microprofile.humboldt;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigBuilder;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The otel.* properties of MicroProfile Config are read through the Config API, not by reflection, when the Config
 * module is in the runtime (BUG-20261102-02).
 */
class MpConfigBridgeTest {

    /** A Config with two properties, which records whether it was reached through {@code Method.invoke}. */
    static final class RecordingConfig implements Config {
        static volatile boolean reachedThroughMethodInvoke;

        private final Map<String, String> values = Map.of("otel.service.name", "orders", "app.name", "shop");

        private static void record() {
            // only the frames between this call and the test: JUnit calls the test method by reflection
            reachedThroughMethodInvoke |= Arrays.stream(Thread.currentThread().getStackTrace())
                    .takeWhile(f -> !f.getClassName().equals(MpConfigBridgeTest.class.getName()))
                    .anyMatch(f -> f.getClassName().equals("java.lang.reflect.Method") && f.getMethodName().equals("invoke"));
        }

        @Override
        public Iterable<String> getPropertyNames() {
            record();
            return values.keySet();
        }

        @Override
        public <T> Optional<T> getOptionalValue(String propertyName, Class<T> propertyType) {
            record();
            return Optional.ofNullable(values.get(propertyName)).map(propertyType::cast);
        }

        @Override
        public <T> T getValue(String propertyName, Class<T> propertyType) {
            return getOptionalValue(propertyName, propertyType).orElseThrow();
        }

        @Override
        public ConfigValue getConfigValue(String propertyName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<ConfigSource> getConfigSources() {
            return List.of();
        }

        @Override
        public <T> Optional<Converter<T>> getConverter(Class<T> forType) {
            return Optional.empty();
        }

        @Override
        public <T> T unwrap(Class<T> type) {
            throw new IllegalArgumentException();
        }
    }

    static final class Resolver extends ConfigProviderResolver {
        final Config config = new RecordingConfig();
        /** How many calls answer no Config before the others answer {@link #config}. */
        int failures;

        Resolver(int failures) {
            this.failures = failures;
        }

        @Override
        public Config getConfig() {
            if (failures > 0) {
                failures--;
                throw new IllegalStateException("no Config yet");
            }
            return config;
        }

        @Override
        public Config getConfig(ClassLoader loader) {
            return config;
        }

        @Override
        public ConfigBuilder getBuilder() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void registerConfig(Config config, ClassLoader classLoader) {
            // a single config
        }

        @Override
        public void releaseConfig(Config config) {
            // a single config
        }
    }

    @AfterEach
    void forgetTheResolver() {
        ConfigProviderResolver.setInstance(null);
    }

    @Test
    void theOtelPropertiesOfMicroProfileConfigAreReadWithoutReflection() {
        ConfigProviderResolver.setInstance(new Resolver(0));
        RecordingConfig.reachedThroughMethodInvoke = false;
        Map<String, String> env = new LinkedHashMap<>();

        HumboldtExtension.bridgeFromMpConfig(env);

        assertEquals(Map.of("OTEL_SERVICE_NAME", "orders"), env);
        assertFalse(RecordingConfig.reachedThroughMethodInvoke, "MicroProfile Config was called through Method.invoke");
    }

    @Test
    void whenTheApiAnswersNoConfigTheReflectiveReadingStillRuns() {
        ConfigProviderResolver.setInstance(new Resolver(1));
        RecordingConfig.reachedThroughMethodInvoke = false;
        Map<String, String> env = new LinkedHashMap<>();

        HumboldtExtension.bridgeFromMpConfig(env);

        assertEquals(Map.of("OTEL_SERVICE_NAME", "orders"), env);
        assertTrue(RecordingConfig.reachedThroughMethodInvoke, "the reflective fallback did not run");
    }

    @Test
    void withoutAnyConfigNothingIsBridgedAndNothingFails() {
        ConfigProviderResolver.setInstance(new Resolver(2));
        Map<String, String> env = new LinkedHashMap<>();

        HumboldtExtension.bridgeFromMpConfig(env);

        assertEquals(Map.of(), env);
    }
}
