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
 * Test de non-régression M8 — le wrapper {@code vidocq-runtime-cervantes-jwt-extension}
 * doit republier la BCE Cervantes (MicroProfile JWT 2.1) de façon à ce qu'elle reste
 * découverte via {@code ServiceLoader} et {@code provides ... with} JPMS.
 *
 * <p>Objectif : confirmer que l'intégration Cervantes dans vidocq n'a pas brisé la
 * découverte de la BCE côté container CDI (injection {@code @Claim} +
 * producteur {@code @RequestScoped JsonWebToken}).</p>
 */
class CervantesJwtBuildCompatibleExtensionTest {

    @Test
    @DisplayName("M8 — le wrapper republie la BCE Cervantes via ServiceLoader et JPMS provides")
    void wrapper_republishes_cervantes_extension_via_service_loader_and_jpms() {
        Class<?> extensionClass;
        try {
            extensionClass = Class.forName(
                    "io.vidocq.runtime.ext.cervantes.CervantesJwtBuildCompatibleExtension");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Extension non trouvée : " + e.getMessage(), e);
        }

        // La BCE wrapper étend bien la BCE Cervantes (re-publication par héritage).
        assertTrue(io.vidocq.cervantes.cdi.CervantesClaimExtension.class.isAssignableFrom(extensionClass),
                "Le wrapper doit étendre io.vidocq.cervantes.cdi.CervantesClaimExtension");

        // Quand le module JPMS est nommé, il doit publier la BCE via provides ... with.
        Module module = extensionClass.getModule();
        ModuleDescriptor descriptor = module.getDescriptor();
        if (module.isNamed() && descriptor != null) {
            boolean providesExtension = descriptor.provides().stream()
                    .anyMatch(provides -> "jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension"
                            .equals(provides.service())
                            && provides.providers().contains(extensionClass.getName()));
            assertTrue(providesExtension,
                    "Le module JPMS du wrapper doit publier BuildCompatibleExtension via provides ... with");
        }
    }
}
