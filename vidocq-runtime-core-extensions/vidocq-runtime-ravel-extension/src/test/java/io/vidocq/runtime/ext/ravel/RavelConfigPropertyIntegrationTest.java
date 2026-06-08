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
package io.vidocq.runtime.ext.ravel;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that {@code @ConfigProperty} works in the Vauban container when
 * this extension is on the path module. Test moved from vidocq-runtime-core
 * as part of core/Ravel decoupling — @ConfigProperty is now a
 * opt-in of {@code vidocq-runtime-ravel-extension}.
 */
@DisplayName("Ravel @ConfigProperty - integration via vidocq-runtime-ravel-extension")
class RavelConfigPropertyIntegrationTest {

    @AfterEach
    void cleanup() {
        var resolver = ConfigProviderResolver.instance();
        var cfg = resolver.getConfig(getClass().getClassLoader());
        resolver.releaseConfig(cfg);
    }

    @Test
    @DisplayName("@ConfigProperty resolves values through the Vauban container")
    void configPropertyResolvedInVaubanContainer() {
        var resolver = ConfigProviderResolver.instance();
        var config = resolver.getBuilder()
                .withSources(new MapConfigSource(Map.of(
                        "vidocq.app.name", "Vidocq Runtime",
                        "vidocq.app.port", "9090"
                )))
                .forClassLoader(getClass().getClassLoader())
                .build();
        resolver.registerConfig(config, getClass().getClassLoader());

        var initializer = SeContainerInitializer.newInstance()
                .addBeanClasses(ConfigConsumerBean.class,
                        io.vidocq.ravel.cdi.ConfigCdiExtension.class);

        try (SeContainer container = initializer.initialize()) {
            var bean = container.select(ConfigConsumerBean.class).get();
            assertNotNull(bean);
            assertEquals("Vidocq Runtime", bean.appName);
            assertEquals(Integer.valueOf(9090), bean.appPort);
            assertTrue(bean.appEnv.isEmpty(), "app.env is absent -> Optional.empty()");
        }
    }

    @Dependent
    public static class ConfigConsumerBean {
        @Inject
        @ConfigProperty(name = "vidocq.app.name", defaultValue = "unknown")
        public String appName;

        @Inject
        @ConfigProperty(name = "vidocq.app.port", defaultValue = "8080")
        public Integer appPort;

        @Inject
        @ConfigProperty(name = "vidocq.app.env")
        public Optional<String> appEnv;
    }

    private static final class MapConfigSource
            implements org.eclipse.microprofile.config.spi.ConfigSource {
        private final Map<String, String> values;

        MapConfigSource(Map<String, String> values) {
            this.values = Map.copyOf(values);
        }

        @Override public Map<String, String> getProperties() { return values; }
        @Override public Set<String> getPropertyNames() { return values.keySet(); }
        @Override public String getValue(String key) { return values.get(key); }
        @Override public String getName() { return "test-source"; }
        @Override public int getOrdinal() { return 1000; }
    }
}
