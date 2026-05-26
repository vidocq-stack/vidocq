package io.vidocq.grimm.cdi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GrimmExtensionTest {

    @AfterEach
    void cleanup() {
        GrimmExtension.resetForTesting();
    }

    @Test
    void discoveredTypes_resolvesRecordedNames() {
        GrimmExtension.recordForTesting(GrimmExtensionTest.class.getName());
        GrimmExtension.recordForTesting(String.class.getName());

        var types = GrimmExtension.discoveredTypes();
        assertTrue(types.contains(GrimmExtensionTest.class));
        assertTrue(types.contains(String.class));
    }
}

