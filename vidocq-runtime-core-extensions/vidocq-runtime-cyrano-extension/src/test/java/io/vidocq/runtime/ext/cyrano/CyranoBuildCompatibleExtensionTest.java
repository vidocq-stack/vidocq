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
 * Test de non-régression M5 — le wrapper {@code vidocq-runtime-cyrano-extension}
 * doit republier la BCE Cyrano de façon à ce qu'elle reste découverte via
 * {@code ServiceLoader} et {@code provides ... with} JPMS.
 *
 * <p>Objectif : confirmer que l'intégration Cyrano dans vidocq n'a pas
 * brisé la découverte de la BCE côté container CDI.</p>
 */
class CyranoBuildCompatibleExtensionTest {

    @Test
    @DisplayName("M5 — le wrapper republiera la BCE Cyrano via ServiceLoader et JPMS provides")
    void wrapper_republishes_cyrano_extension_via_service_loader_and_jpms() {
        // Charger la classe de l'extension via réflexion
        Class<?> extensionClass;
        try {
            extensionClass = Class.forName("io.vidocq.runtime.ext.cyrano.CyranoBuildCompatibleExtension");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Extension non trouvée : " + e.getMessage(), e);
        }

        // Valider que le module JPMS exporte la BCE
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



