/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
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



