package io.vidocq.runtime.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PropertiesFileConfigSourceTest {

    @Test
    void ordinalIs100() {
        assertEquals(100, new PropertiesFileConfigSource().getOrdinal());
    }

    @Test
    void readsFromTestClasspathVidocqProperties() {
        PropertiesFileConfigSource src = new PropertiesFileConfigSource();
        assertEquals("from-vidocq", src.getValue("vidocq.test.file.key"));
    }

    @Test
    void readsFromApplicationProperties() {
        PropertiesFileConfigSource src = new PropertiesFileConfigSource();
        assertEquals("from-app", src.getValue("vidocq.test.app.key"));
    }

    @Test
    void returnsNullWhenKeyAbsent() {
        assertNull(new PropertiesFileConfigSource().getValue("totally.absent.key"));
    }

    @Test
    void propertyNamesExposesAllKeys() {
        PropertiesFileConfigSource src = new PropertiesFileConfigSource();
        assertTrue(src.getPropertyNames().contains("vidocq.test.file.key"));
        assertTrue(src.getPropertyNames().contains("vidocq.test.app.key"));
    }
}
