/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.runtime.ext.cervantes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.module.ModuleDescriptor;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M8 non-regression test — the wrapper {@code vidocq-runtime-cervantes-jwt-extension}
 * must republish the ECB Cervantes (MicroProfile JWT 2.1) so that it remains
 * discovered via {@code ServiceLoader} and {@code provides ... with} JPMS.
 *
 * <p>Objective: to confirm that the Cervantes integration in vidocq did not break the
 * discovery of the ECB on the CDI container side (injection {@code @Claim} +
 * producer {@code @RequestScoped JsonWebToken}).</p>
 */
class CervantesJwtBuildCompatibleExtensionTest {

    @Test
    @DisplayName("M8 - the wrapper republishes the Cervantes BCE via ServiceLoader and JPMS provides")
    void wrapper_republishes_cervantes_extension_via_service_loader_and_jpms() {
        Class<?> extensionClass;
        try {
            extensionClass = Class.forName(
                    "io.vidocq.runtime.ext.cervantes.CervantesJwtBuildCompatibleExtension");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Extension not found: " + e.getMessage(), e);
        }

        // The ECB wrapper extends the ECB Cervantes well (re-publication by inheritance).
        assertTrue(io.vidocq.cervantes.cdi.CervantesClaimExtension.class.isAssignableFrom(extensionClass),
                "The wrapper must extend io.vidocq.cervantes.cdi.CervantesClaimExtension");

        // When the JPMS module is named, it must publish the ECB via provides ... with.
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
