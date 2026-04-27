package io.vidocq.mpserver.core.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SystemPropertiesConfigSourceTest {

    private static final String KEY = "vidocq.test.systemprops.key";

    @AfterEach
    void cleanup() {
        System.clearProperty(KEY);
    }

    @Test
    void ordinalIs400() {
        assertEquals(400, new SystemPropertiesConfigSource().getOrdinal());
    }

    @Test
    void readsSystemProperty() {
        System.setProperty(KEY, "hello");
        assertEquals("hello", new SystemPropertiesConfigSource().getValue(KEY));
    }

    @Test
    void returnsNullWhenAbsent() {
        assertNull(new SystemPropertiesConfigSource().getValue("definitely.not.set." + System.nanoTime()));
    }

    @Test
    void propertyNamesIncludesSetKey() {
        System.setProperty(KEY, "v");
        assertTrue(new SystemPropertiesConfigSource().getPropertyNames().contains(KEY));
    }
}
