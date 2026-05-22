package io.vidocq.runtime.ext.mansart.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VID-2 — minimal isolated checks. The end-to-end behaviour (DataSource probe + repository
 * inventory at boot) is exercised by the bout-en-bout example {@code vidocq-runtime-mansart-h2-example}
 * because it requires a real Vauban container.
 */
class MansartDataIntegrationExtensionTest {

    private final MansartDataIntegrationExtension ext = new MansartDataIntegrationExtension();

    @Test
    void identityAndPriority() {
        assertEquals("mansart-data", ext.name());
        // Must run after the pool (200) and before transports (Cassini = 500).
        assertTrue(ext.priority() > 200, "must boot after the pool extension");
        assertTrue(ext.priority() < 500, "must boot before HTTP transports");
    }
}
