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
package io.vidocq.runtime.arquillian;

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link VidocqCdiTestEnricher}: Arquillian test-class enrichment
 * resolved through the public {@link VaubanContainer} lookup API — the TCK
 * test classes get their {@code @Inject} fields populated without any
 * implementation-internal wiring or reflection over runtime internals.
 */
class VidocqCdiTestEnricherTest {

    @ApplicationScoped
    public static class GreetingBean {
        public String greet() {
            return "hello";
        }
    }

    public static class FakeTestCase {
        @Inject
        GreetingBean greeting;

        GreetingBean notInjectable;
    }

    private static VaubanContainer container;

    @BeforeAll
    static void bootContainer() {
        container = VaubanContainer.builder()
                .addBeanClass(GreetingBean.class)
                .build();
    }

    @AfterAll
    static void stopContainer() {
        if (container != null) {
            container.close();
        }
    }

    @Test
    void injectsAnnotatedFieldsFromTheRunningContainer() {
        FakeTestCase testCase = new FakeTestCase();

        new VidocqCdiTestEnricher().enrich(testCase);

        assertNotNull(testCase.greeting, "@Inject field must be populated");
        assertEquals("hello", testCase.greeting.greet());
        assertNull(testCase.notInjectable, "fields without @Inject must be left alone");
    }

    @Test
    void resolveReturnsNullsForUnhandledMethodParameters() throws Exception {
        var method = FakeTestCase.class.getMethod("hashCode");
        assertEquals(0, new VidocqCdiTestEnricher().resolve(method).length);
    }
}
