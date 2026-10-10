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
package io.vidocq.it.grimm.weld.twocontainers;

import io.vidocq.grimm.cdi.GrimmAutoDiscovery;
import io.vidocq.grimm.cdi.GrimmConfigProducer;
import io.vidocq.grimm.cdi.GrimmModelCache;
import io.vidocq.grimm.cdi.OpenApiResource;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two containers in one JVM, sharing the Grimm classes, keep their documents apart (grimm#22):
 * the classes the extension discovers belong to each container, not to a static field.
 */
class TwoContainersOneJvmTest {

    private static WeldContainer first;
    private static volatile WeldContainer second;

    private static final Class<?>[] GRIMM_BEANS = {
            GrimmModelCache.class, GrimmConfigProducer.class, GrimmAutoDiscovery.class, OpenApiResource.class};

    @BeforeAll
    static void start() {
        first = new Weld("first").disableDiscovery()
                .addBeanClasses(GRIMM_BEANS).addBeanClasses(FirstResource.class, SecondContainerBooter.class)
                .initialize();
    }

    static void startSecond() {
        second = new Weld("second").disableDiscovery()
                .addBeanClasses(GRIMM_BEANS).addBeanClass(SecondResource.class)
                .initialize();
    }

    @AfterAll
    static void stop() {
        if (second != null) {
            second.close();
        }
        if (first != null) {
            first.close();
        }
    }

    @Test
    void eachContainerDocumentsItsOwnResources() {
        String firstDocument = first.select(OpenApiResource.class).get().render("json", null).body();
        String secondDocument = second.select(OpenApiResource.class).get().render("json", null).body();

        assertTrue(firstDocument.contains("\"/first\""), firstDocument);
        assertFalse(firstDocument.contains("\"/second\""), firstDocument);
        assertTrue(secondDocument.contains("\"/second\""), secondDocument);
        assertFalse(secondDocument.contains("\"/first\""), secondDocument);
    }
}
