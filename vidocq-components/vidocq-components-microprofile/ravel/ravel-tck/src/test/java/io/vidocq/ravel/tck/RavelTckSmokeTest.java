/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.tck;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test du module {@code ravel-tck} — vérifie que le {@code ServiceLoader}
 * découvre bien {@code RavelConfigProviderResolver} depuis le classpath gelé
 * (artefact installé localement).
 *
 * <p>Pas d'Arquillian ici : ce test tourne en {@code mvn -f ravel-tck/pom.xml test}
 * sans profil et constitue le garde-fou minimum avant tout run TCK officiel.</p>
 */
@DisplayName("Ravel TCK smoke — ServiceLoader bootstrap (M5)")
class RavelTckSmokeTest {

    @Test
    void serviceLoader_loads_RavelConfigProviderResolver() {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        assertNotNull(resolver, "ConfigProviderResolver.instance() must return a singleton");
        assertEquals(
                "io.vidocq.ravel.internal.RavelConfigProviderResolver",
                resolver.getClass().getName(),
                "Le resolver attendu est celui de Ravel — vérifier le META-INF/services dans ravel-core");
    }

    @Test
    void config_lookup_works_endToEnd() {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        Config config = resolver.getBuilder()
                .addDefaultSources()
                .forClassLoader(getClass().getClassLoader())
                .build();
        try {
            // §3.4 — au minimum, java.version doit être lisible via SystemPropertiesConfigSource (ord. 400)
            String javaVersion = config.getValue("java.version", String.class);
            assertNotNull(javaVersion);
            assertTrue(javaVersion.length() > 0);
        } finally {
            resolver.releaseConfig(config);
        }
    }
}

