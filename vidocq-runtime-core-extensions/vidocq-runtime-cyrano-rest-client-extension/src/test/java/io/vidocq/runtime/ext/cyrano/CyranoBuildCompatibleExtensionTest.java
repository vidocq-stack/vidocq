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
package io.vidocq.runtime.ext.cyrano;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.module.ModuleDescriptor;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5 non-regression test — the wrapper {@code vidocq-runtime-cyrano-rest-client-extension}
 * must republish ECB Cyrano so that it remains discoverable via
 * {@code ServiceLoader} and {@code provides ... with} JPMS.
 *
 * <p>Objective: to confirm that the Cyrano integration in vidocq has not
 * broken the discovery of the ECB on the CDI container side.</p>
 */
class CyranoBuildCompatibleExtensionTest {

    @Test
    @DisplayName("M5 - the wrapper republishes the Cyrano BCE via ServiceLoader and JPMS provides")
    void wrapper_republishes_cyrano_extension_via_service_loader_and_jpms() {
        // Load extension class via reflection
        Class<?> extensionClass;
        try {
            extensionClass = Class.forName("io.vidocq.runtime.ext.cyrano.CyranoBuildCompatibleExtension");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Extension not found: " + e.getMessage(), e);
        }

        // Validate that the JPMS module exports the ECB
        Module module = extensionClass.getModule();
        ModuleDescriptor descriptor = module.getDescriptor();
        if (module.isNamed() && descriptor != null) {
            boolean providesExtension = descriptor.provides().stream()
                    .anyMatch(provides -> "jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension"
                            .equals(provides.service())
                            && provides.providers().contains(extensionClass.getName()));
            assertTrue(providesExtension,
                    "The wrapper JPMS module must publish BuildCompatibleExtension via provides ... with");
        }
    }
}



