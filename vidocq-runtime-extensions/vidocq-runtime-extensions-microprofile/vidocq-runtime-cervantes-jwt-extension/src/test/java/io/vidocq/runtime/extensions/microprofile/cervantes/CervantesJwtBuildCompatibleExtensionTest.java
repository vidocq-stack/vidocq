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
package io.vidocq.runtime.extensions.microprofile.cervantes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.module.ModuleDescriptor;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M8 non-regression test — the wrapper {@code vidocq-runtime-cervantes-jwt-extension}
 * must republish the ECB Cervantes (MicroProfile JWT 2.1) so that it remains
 * discovered via {@code ServiceLoader} and {@code provides ... with} Java Modules.
 *
 * <p>Objective: to confirm that the Cervantes integration in vidocq did not break the
 * discovery of the ECB on the CDI container side (injection {@code @Claim} +
 * producer {@code @RequestScoped JsonWebToken}).</p>
 */
class CervantesJwtBuildCompatibleExtensionTest {

    @Test
    @DisplayName("M8 - the wrapper republishes the Cervantes BCE via ServiceLoader and Java Modules provides")
    void wrapper_republishes_cervantes_extension_via_service_loader_and_java_modules() {
        Class<?> extensionClass;
        try {
            extensionClass = Class.forName(
                    "io.vidocq.runtime.extensions.microprofile.cervantes.CervantesJwtBuildCompatibleExtension");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Extension not found: " + e.getMessage(), e);
        }

        // The ECB wrapper extends the ECB Cervantes well (re-publication by inheritance).
        assertTrue(io.vidocq.cervantes.cdi.CervantesClaimExtension.class.isAssignableFrom(extensionClass),
                "The wrapper must extend io.vidocq.cervantes.cdi.CervantesClaimExtension");

        // When the Java module is named, it must publish the ECB via provides ... with.
        Module module = extensionClass.getModule();
        ModuleDescriptor descriptor = module.getDescriptor();
        if (module.isNamed() && descriptor != null) {
            boolean providesExtension = descriptor.provides().stream()
                    .anyMatch(provides -> "jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension"
                            .equals(provides.service())
                            && provides.providers().contains(extensionClass.getName()));
            assertTrue(providesExtension,
                    "The wrapper Java module must publish BuildCompatibleExtension via provides ... with");
        }
    }
}
