/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.cdi;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * Couverture du chemin BCE complet sous Vauban — découverte de
     * {@link ConfigCdiExtension} via ServiceLoader, phase {@code @Registration}
     * qui collecte les IPs {@code @ConfigProperty}, phase {@code @Synthesis}
     * qui enregistre les SyntheticBean correspondants. Cible la même surface
     * d'API que {@code cassini-examples-vauban/ConfigDemoResource}.
     *
     * <p><b>Désactivé (M6 résiduel)</b> — le pipeline BCE de Vauban 0.1.0-SNAPSHOT
     * n'invoque pas les phases {@code @Registration}/{@code @Synthesis} de notre
     * extension dans cette configuration ({@link SeContainerInitializer} +
     * {@code addBeanClasses}, ou {@link io.vidocq.vauban.core.container.VaubanContainer#builder()}
     * + {@code scanClasspath()}). Les preuves :</p>
     * <ul>
     *   <li>aucun debug println depuis {@code ConfigCdiExtension.@Registration} /
     *       {@code @Synthesis} n'est imprimé pendant le déploiement ;</li>
     *   <li>la même {@link ConfigCdiExtension} passe **349/349** tests
     *       MicroProfile Config 3.1 TCK sous Weld 6.0.2 (cf. {@code TCK.md}) —
     *       donc le code Ravel est conforme spec ;</li>
     *   <li>{@code cassini-examples-vauban/ConfigDemoResourceTest} échoue avec
     *       le même symptôme ({@code WELD-001408 / Unsatisfied dependency}).</li>
     * </ul>
     * <p>À réactiver une fois le bug Vauban-side traité (à investiguer côté
     * {@code vauban-core} : pourquoi {@code BceProcessor.process(...)} ne
     * dispatche pas {@code @Registration} pour les beans découverts via
     * {@code addBeanClasses}).</p>
     */
    @Test
    @Disabled("M6 résiduel — pipeline BCE Vauban n'invoque pas @Registration/@Synthesis (voir Javadoc)")
    void resolves_config_property_injection_through_bce_pipeline() {
        registerConfig(Map.of(
                "app.greeting", "Bonjour",
                "app.version", "1.2.3"
                // app.env volontairement absent pour le cas Optional
        ));

        // Note : sous Vauban, SeContainerInitializer.addBeanClasses(...) ne déclenche pas
        // l'auto-scan des BCE via META-INF/services. On enregistre la BCE explicitement
        // — équivalent au scan classpath qu'effectuent Cassini/Chappe via VaubanContainer.builder().scanClasspath().
        SeContainerInitializer initializer = SeContainerInitializer.newInstance()
                .addBeanClasses(ConfigPropertyTestBean.class, ConfigCdiExtension.class);

        try (SeContainer container = initializer.initialize()) {
            ConfigPropertyTestBean bean = container.select(ConfigPropertyTestBean.class).get();
            assertNotNull(bean);
            assertEquals("Bonjour", bean.greeting);
            assertEquals("1.2.3", bean.version);
            assertTrue(bean.environment.isEmpty(), "app.env absent → Optional.empty()");
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

    @Dependent
    public static class ConfigPropertyTestBean {
        @Inject
        @ConfigProperty(name = "app.greeting", defaultValue = "Hello")
        public String greeting;

        @Inject
        @ConfigProperty(name = "app.version", defaultValue = "0.0.0")
        public String version;

        @Inject
        @ConfigProperty(name = "app.env")
        public Optional<String> environment;
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



