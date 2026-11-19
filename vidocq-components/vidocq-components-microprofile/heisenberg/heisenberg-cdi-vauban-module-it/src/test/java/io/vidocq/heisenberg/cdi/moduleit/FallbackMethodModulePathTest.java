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
package io.vidocq.heisenberg.cdi.moduleit;

import io.vidocq.heisenberg.cdi.internal.BulkheadStateRegistryBean;
import io.vidocq.heisenberg.cdi.internal.FaultToleranceInterceptor;
import io.vidocq.heisenberg.cdi.internal.HeisenbergExtension;
import io.vidocq.heisenberg.cdi.internal.StateRegistryBean;
import io.vidocq.vauban.core.container.VaubanContainer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MP FT 4.1 §6 (Fallback): a {@code fallbackMethod} declared {@code private} or package-private in a
 * bean of a named application module that opens nothing must be invoked on the strict module path.
 *
 * <p>The guard checks first that the module really is strict for Heisenberg: its package is not
 * open to {@code io.vidocq.heisenberg.core}. Surefire opens test packages to {@code ALL-UNNAMED}
 * only (for JUnit), which a named module does not benefit from.</p>
 */
class FallbackMethodModulePathTest {

    @Test
    void invokes_private_and_package_private_fallback_methods_without_opens() {
        Module app = FallbackService.class.getModule();
        Module core = FallbackMethodModulePathTest.class.getModule().getLayer()
                .findModule("io.vidocq.heisenberg.core").orElseThrow();
        assertTrue(app.isNamed(), "the fixture must run in a named module");
        assertFalse(app.isOpen(FallbackService.class.getPackageName(), core),
                "the fixture package must not be open to io.vidocq.heisenberg.core");

        VaubanContainer container = VaubanContainer.builder()
                .addBeanClass(HeisenbergExtension.class)
                .addBeanClass(FaultToleranceInterceptor.class)
                .addBeanClass(StateRegistryBean.class)
                .addBeanClass(BulkheadStateRegistryBean.class)
                .addBeanClass(FallbackService.class)
                .build();
        try {
            FallbackService service = container.select(FallbackService.class);
            assertEquals("private fallback for duke", service.callWithPrivateFallback("duke"));
            assertEquals("package-private fallback for duke", service.callWithPackagePrivateFallback("duke"));
        } finally {
            container.close();
        }
    }
}
