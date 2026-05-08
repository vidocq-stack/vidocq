/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.cdi;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("Vauban container integration — @ConfigProperty + Config")
class VaubanContainerIntegrationTest {

    @AfterEach
    void cleanup() {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        Config cfg = resolver.getConfig(getClass().getClassLoader());
        resolver.releaseConfig(cfg);
    }

    @Test
    void launches_vauban_container_and_resolves_config_injections() {
        registerConfig(Map.of(
                "app.name", "ravel",
                "app.port", "8181"
        ));

        SeContainerInitializer initializer = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addBeanClasses(RavelConfigProducer.class, TestBean.class);

        try (SeContainer container = initializer.initialize()) {
            TestBean bean = container.select(TestBean.class).get();
            assertNotNull(bean);
            assertNotNull(bean.config);
            assertEquals("ravel", bean.config.getValue("app.name", String.class));
            assertEquals(8181, bean.config.getValue("app.port", Integer.class));
        }
    }

    private void registerConfig(Map<String, String> values) {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        Config custom = resolver.getBuilder()
                .withSources(new TestSource(values))
                .forClassLoader(getClass().getClassLoader())
                .build();
        resolver.registerConfig(custom, getClass().getClassLoader());
    }

    @Dependent
    static class TestBean {
        @Inject
        Config config;
    }

    private static final class TestSource implements ConfigSource {
        private final Map<String, String> values;

        private TestSource(Map<String, String> values) {
            this.values = Map.copyOf(values);
        }

        @Override
        public Map<String, String> getProperties() {
            return values;
        }

        @Override
        public Set<String> getPropertyNames() {
            return values.keySet();
        }

        @Override
        public String getValue(String propertyName) {
            return values.get(propertyName);
        }

        @Override
        public String getName() {
            return "vauban-it-source";
        }

        @Override
        public int getOrdinal() {
            return 1000;
        }
    }
}



