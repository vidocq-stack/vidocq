package io.vidocq.grimm.cdi;

import io.vidocq.grimm.internal.config.GrimmConfig;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Embedded Vauban CDI integration test for Grimm beans.
 *
 * Spec intent (M9): GrimmModelCache is CDI-managed and injectable in an embedded
 * container; the produced model is available for the endpoint lifecycle.
 */
class GrimmVaubanIntegrationTest {

    @Test
    void vaubanContainer_canInjectGrimmModelCache() {
        try (VaubanContainer container = VaubanContainer.builder()
                .addBeanClass(GrimmModelCache.class)
                .addBeanClass(TestSupportProducer.class)
                .build()) {
            GrimmModelCache cache = container.select(GrimmModelCache.class);
            assertNotNull(cache);

            var model = cache.getDocument();
            assertNotNull(model);
            // Baseline contract: model exists and has an OpenAPI version.
            assertEquals("3.1.0", model.getOpenapi());
            assertTrue(model.getOpenapi().startsWith("3."));
        }
    }

    @Dependent
    static class TestSupportProducer {
        @Produces
        GrimmConfig grimmConfig() {
            return GrimmConfig.defaults();
        }

        @Produces
        ScannedTypes scannedTypes() {
            return ScannedTypes.empty();
        }
    }
}



