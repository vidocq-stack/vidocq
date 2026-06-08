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
package io.vidocq.heisenberg.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.junit.jupiter.api.Test;

class ConfigResolverRetryTest {

    @Test
    void usesMethodLevelConfigBeforeClassAndGlobal() throws Exception {
        // MP FT 4.1 §9: method > class > global.
        Map<String, String> values = new HashMap<>();
        String className = ConfiguredService.class.getName();
        values.put("Retry/maxRetries", "1");
        values.put(className + "/Retry/maxRetries", "2");
        values.put(className + "/call/Retry/maxRetries", "4");

        Method method = ConfiguredService.class.getDeclaredMethod("call");
        Retry retry = method.getAnnotation(Retry.class);

        RetryConfig config = ConfigResolver.retryConfig(method, retry, new FakeConfig(values));

        assertEquals(4, config.maxRetries());
    }

    @Retry(maxRetries = 3)
    static class ConfiguredService {
        @Retry(maxRetries = 3)
        void call() {}
    }

    private static final class FakeConfig implements Config {
        private final Map<String, String> values;

        private FakeConfig(Map<String, String> values) {
            this.values = values;
        }

        @Override
        public <T> T getValue(String propertyName, Class<T> propertyType) {
            return getOptionalValue(propertyName, propertyType)
                    .orElseThrow(() -> new IllegalArgumentException("Missing property: " + propertyName));
        }

        @Override
        public ConfigValue getConfigValue(String propertyName) {
            throw new UnsupportedOperationException();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<T> getOptionalValue(String propertyName, Class<T> propertyType) {
            String value = values.get(propertyName);
            if (value == null) {
                return Optional.empty();
            }
            if (propertyType == String.class) {
                return Optional.of((T) value);
            }
            if (propertyType == Integer.class) {
                return Optional.of((T) Integer.valueOf(value));
            }
            if (propertyType == Long.class) {
                return Optional.of((T) Long.valueOf(value));
            }
            throw new UnsupportedOperationException("Unsupported type: " + propertyType.getName());
        }

        @Override
        public Iterable<String> getPropertyNames() {
            return values.keySet();
        }

        @Override
        public Iterable<org.eclipse.microprofile.config.spi.ConfigSource> getConfigSources() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> Optional<org.eclipse.microprofile.config.spi.Converter<T>> getConverter(Class<T> forType) {
            return Optional.empty();
        }

        @Override
        public <T> T unwrap(Class<T> type) {
            throw new UnsupportedOperationException();
        }
    }
}

