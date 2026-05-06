/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.internal;

import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MicroprofilePropertiesConfigSource — built-in ord. 100 (MP Config 3.1 §3.4)")
class MicroprofilePropertiesConfigSourceTest {

    @Test
    void loadAll_returns_at_least_one_source_with_fixture_keys() {
        // §3.4 — META-INF/microprofile-config.properties est scanné depuis le ClassLoader.
        var sources = MicroprofilePropertiesConfigSource.loadAll(getClass().getClassLoader());

        assertNotNull(sources);
        assertFalse(sources.isEmpty(),
                "La fixture src/test/resources/META-INF/microprofile-config.properties doit être chargée");

        boolean found = false;
        for (var src : sources) {
            if ("hello-from-properties".equals(src.getValue("ravel.test.props.greeting"))) {
                found = true;
                break;
            }
        }
        assertTrue(found, "La fixture contient ravel.test.props.greeting=hello-from-properties");
    }

    @Test
    void ordinal_is_100() {
        var sources = MicroprofilePropertiesConfigSource.loadAll(getClass().getClassLoader());
        assertFalse(sources.isEmpty());
        for (var src : sources) {
            assertEquals(100, src.getOrdinal(),
                    "Toutes les MicroprofilePropertiesConfigSource ont ordinal 100");
        }
    }

    @Test
    void name_includes_url_for_diagnostics() {
        var sources = MicroprofilePropertiesConfigSource.loadAll(getClass().getClassLoader());
        for (var src : sources) {
            assertTrue(src.getName().startsWith("MicroprofilePropertiesConfigSource"),
                    "Nom canonique préfixé : " + src.getName());
        }
    }

    @Test
    void absent_key_returns_null() {
        var sources = MicroprofilePropertiesConfigSource.loadAll(getClass().getClassLoader());
        for (var src : sources) {
            assertNull(src.getValue("absent.key.no.match"));
        }
    }

    @Test
    void getPropertyNames_includes_fixture_keys() {
        var sources = MicroprofilePropertiesConfigSource.loadAll(getClass().getClassLoader());
        boolean foundKey = false;
        for (var src : sources) {
            if (src.getPropertyNames().contains("ravel.test.props.greeting")) {
                foundKey = true;
                break;
            }
        }
        assertTrue(foundKey);
    }

    @Test
    void implements_ConfigSource() {
        List<MicroprofilePropertiesConfigSource> sources =
                MicroprofilePropertiesConfigSource.loadAll(getClass().getClassLoader());
        for (var src : sources) {
            assertInstanceOf(ConfigSource.class, src);
        }
    }

    @Test
    void loadAll_with_classloader_without_resource_returns_empty_list() throws Exception {
        // ClassLoader vide : pas de fichier META-INF/microprofile-config.properties → liste vide.
        var emptyCl = new java.net.URLClassLoader(new java.net.URL[0], null);
        var sources = MicroprofilePropertiesConfigSource.loadAll(emptyCl);
        assertNotNull(sources);
        assertTrue(sources.isEmpty(), "Empty CL → no MP properties files");
        emptyCl.close();
    }
}
