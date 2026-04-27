package io.vidocq.mpserver.ext.chappe;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChappeEngineExtensionTest {

    @AfterEach
    void cleanup() {
        ChappeMountPoint.uninstall();
    }

    @Test
    void configureInstallsMountPoint() {
        new ChappeEngineExtension().configure(null);
        assertNotNull(ChappeMountPoint.instance());
    }

    @Test
    void priorityIsOneHundred() {
        assertEquals(100, new ChappeEngineExtension().priority());
    }

    @Test
    void nameIsChappeEngine() {
        assertEquals("chappe-engine", new ChappeEngineExtension().name());
    }
}
