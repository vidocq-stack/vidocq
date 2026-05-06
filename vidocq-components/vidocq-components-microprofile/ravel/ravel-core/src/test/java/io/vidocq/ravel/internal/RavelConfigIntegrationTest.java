/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.internal;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test d'intégration M1 — vérifie que les 3 sources canoniques cascadent correctement
 * avec un {@code Config} obtenu via {@link ConfigProvider#getConfig()}.
 */
@DisplayName("Ravel — intégration end-to-end M1 (3 sources canoniques)")
class RavelConfigIntegrationTest {

    private static final String IT_KEY = "ravel.it.k";

    @AfterEach
    void cleanup() {
        System.clearProperty(IT_KEY);
        // Libère le Config courant pour ne pas leaker l'état modifié entre tests.
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        Config current = resolver.getConfig();
        resolver.releaseConfig(current);
    }

    @Test
    void ConfigProvider_returns_RavelConfig() {
        Config cfg = ConfigProvider.getConfig();
        assertNotNull(cfg);
        assertInstanceOf(RavelConfig.class, cfg);
    }

    @Test
    void system_property_lookup_via_ConfigProvider() {
        // Bout-en-bout : SystemPropertiesConfigSource (ord. 400) gagne tout le reste.
        System.setProperty(IT_KEY, "from-sysprops");

        Config cfg = ConfigProvider.getConfig();
        assertEquals("from-sysprops", cfg.getValue(IT_KEY, String.class));
    }

    @Test
    void microprofile_properties_fixture_is_visible() {
        // La fixture src/test/resources/META-INF/microprofile-config.properties est sur le classpath.
        Config cfg = ConfigProvider.getConfig();
        assertEquals("hello-from-properties",
                cfg.getValue("ravel.test.props.greeting", String.class));
    }

    @Test
    void cascade_sysprops_wins_over_microprofile_properties() {
        // Une clé présente à la fois dans SysProps (400) et MPProps (100).
        // L'ordinal le plus haut gagne.
        System.setProperty("ravel.test.props.greeting", "from-sysprops-override");

        Config cfg = ConfigProvider.getConfig();
        try {
            assertEquals("from-sysprops-override",
                    cfg.getValue("ravel.test.props.greeting", String.class));
        } finally {
            System.clearProperty("ravel.test.props.greeting");
        }
    }

    @Test
    void config_sources_iteration_includes_all_three() {
        Config cfg = ConfigProvider.getConfig();
        var sourceNames = new HashMap<String, Integer>();
        for (var s : cfg.getConfigSources()) {
            sourceNames.put(s.getName(), s.getOrdinal());
        }
        assertTrue(sourceNames.containsKey("SystemPropertiesConfigSource"));
        assertEquals(400, sourceNames.get("SystemPropertiesConfigSource"));
        assertTrue(sourceNames.containsKey("EnvironmentVariablesConfigSource"));
        assertEquals(300, sourceNames.get("EnvironmentVariablesConfigSource"));
        boolean hasMpProps = sourceNames.keySet().stream()
                .anyMatch(n -> n.startsWith("MicroprofilePropertiesConfigSource"));
        assertTrue(hasMpProps);
    }

    @Test
    void getOptionalValue_absent_returns_empty() {
        Config cfg = ConfigProvider.getConfig();
        assertTrue(cfg.getOptionalValue("ravel.it.does.not.exist", String.class).isEmpty());
    }

    @Test
    void custom_built_config_can_replace_default_via_resolver() {
        // §3.5 — getBuilder() permet de produire un Config custom et de
        // l'enregistrer pour le ClassLoader courant.
        var customSource = MapConfigSource.of("itCustom", 999, Map.of("ravel.it.custom", "ok"));
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        Config custom = resolver.getBuilder()
                .withSources(customSource)
                .forClassLoader(getClass().getClassLoader())
                .build();
        resolver.registerConfig(custom, getClass().getClassLoader());

        Config cfg = ConfigProvider.getConfig();
        assertEquals("ok", cfg.getValue("ravel.it.custom", String.class));
    }
}
