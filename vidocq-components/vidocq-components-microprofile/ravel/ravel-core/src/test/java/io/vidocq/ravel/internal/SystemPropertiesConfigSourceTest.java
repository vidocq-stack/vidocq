/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.internal;

import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SystemPropertiesConfigSource — built-in source ordinal 400 (MP Config 3.1 §3.4)")
class SystemPropertiesConfigSourceTest {

    private static final String KEY = "ravel.test.sysprops.key";

    @BeforeEach
    @AfterEach
    void cleanup() {
        System.clearProperty(KEY);
    }

    @Test
    void ordinal_is_400() {
        // §3.4 — System properties have a default ordinal of 400.
        assertEquals(400, new SystemPropertiesConfigSource().getOrdinal());
    }

    @Test
    void name_is_canonical() {
        assertEquals("SystemPropertiesConfigSource", new SystemPropertiesConfigSource().getName());
    }

    @Test
    void getValue_returns_current_system_property() {
        System.setProperty(KEY, "v1");
        var src = new SystemPropertiesConfigSource();
        assertEquals("v1", src.getValue(KEY));
    }

    @Test
    void getValue_reflects_runtime_mutations() {
        // System.getProperties() est mutable runtime — la source doit relire à
        // chaque appel sans cache local.
        var src = new SystemPropertiesConfigSource();
        assertNull(src.getValue(KEY));

        System.setProperty(KEY, "v1");
        assertEquals("v1", src.getValue(KEY));

        System.setProperty(KEY, "v2");
        assertEquals("v2", src.getValue(KEY));

        System.clearProperty(KEY);
        assertNull(src.getValue(KEY));
    }

    @Test
    void getPropertyNames_includes_set_properties() {
        System.setProperty(KEY, "v");
        var src = new SystemPropertiesConfigSource();
        assertTrue(src.getPropertyNames().contains(KEY));
    }

    @Test
    void getProperties_is_a_snapshot_of_current_state() {
        System.setProperty(KEY, "v");
        var src = new SystemPropertiesConfigSource();
        assertEquals("v", src.getProperties().get(KEY));
    }

    @Test
    void implements_ConfigSource() {
        assertInstanceOf(ConfigSource.class, new SystemPropertiesConfigSource());
    }
}
