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

import io.vidocq.vauban.core.container.VaubanContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import test.grimm.app.SamplePojo;
import test.grimm.app.SampleResource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §4.4: the annotation scan covers <em>all application classes</em>, not
 * only JAX-RS resources — {@code @Schema} POJOs and the
 * {@code @OpenAPIDefinition} Application subclass contribute to the model too
 * (TCK {@code AirlinesAppTest} asserts components built from plain POJOs).
 * The BCE must therefore record every application class it sees, while the
 * runtime's own classes ({@code io.vidocq.*}) stay out of the document
 * (e.g. the /openapi endpoint itself must not be listed — spec §2.2).
 *
 * <p>Each container boot represents one deployment: previously collected
 * names must not leak into the next boot (sequential Arquillian TCK
 * deployments share the JVM).</p>
 */
class GrimmExtensionApplicationScanTest {

    @AfterEach
    void cleanup() {
        GrimmExtension.resetForTesting();
    }

    @Test
    void collectsEveryApplicationClassAndSkipsRuntimeClasses() {
        try (VaubanContainer container = VaubanContainer.builder()
                .addBeanClass(GrimmExtension.class)
                .addBeanClass(SampleResource.class)
                .addBeanClass(SamplePojo.class)
                .build()) {

            var types = GrimmExtension.discoveredTypes();
            assertTrue(types.contains(SampleResource.class), "@Path resources must be collected");
            assertTrue(types.contains(SamplePojo.class),
                    "plain application classes (@Schema POJOs) must be collected");
            assertFalse(types.stream().anyMatch(t -> t.getName().startsWith("io.vidocq.grimm.")),
                    "grimm's own classes must not enter the application model");
        }
    }

    @Test
    void eachBootStartsFromAnEmptyCollection() {
        try (VaubanContainer first = VaubanContainer.builder()
                .addBeanClass(GrimmExtension.class)
                .addBeanClass(SamplePojo.class)
                .build()) {
            assertTrue(GrimmExtension.discoveredTypes().contains(SamplePojo.class));
        }

        try (VaubanContainer second = VaubanContainer.builder()
                .addBeanClass(GrimmExtension.class)
                .addBeanClass(SampleResource.class)
                .build()) {
            var types = GrimmExtension.discoveredTypes();
            assertTrue(types.contains(SampleResource.class));
            assertFalse(types.contains(SamplePojo.class),
                    "types from a previous deployment must not leak into the next boot");
        }
    }
}
