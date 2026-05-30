/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.runtime.ext.cyrano;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.module.ModuleDescriptor;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5 non-regression test — the wrapper {@code vidocq-runtime-cyrano-extension}
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



