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
package io.vidocq.ravel.it.weld;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Ravel jars, unchanged, under Weld SE on a class path (vidocq-workspace#15, ravel#25): the
 * build compatible extension synthesises the {@code @ConfigProperty} and {@code @ConfigProperties}
 * beans and the {@code Config} bean, and {@code ConfigProvider} answers with Ravel. Nothing
 * generated for Vauban is used.
 */
class WeldPortabilityTest {

    private static WeldContainer container;

    @BeforeAll
    static void start() {
        container = new Weld().initialize();
    }

    @AfterAll
    static void stop() {
        if (container != null) {
            container.close();
        }
    }

    @Test
    void vaubanIsNotOnTheClassPath() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("io.vidocq.vauban.core.container.VaubanContainer"));
    }

    @Test
    void configPropertyIsInjected() {
        Greeter greeter = container.select(Greeter.class).get();
        assertEquals("hello from ravel", greeter.greeting());
        assertEquals("fallback", greeter.withDefault());
    }

    @Test
    void configPropertiesAreInjected() {
        assertEquals("localhost:8080", container.select(Greeter.class).get().server());
    }

    @Test
    void configBeanAndConfigProviderAreRavel() {
        assertEquals("hello from ravel", container.select(Greeter.class).get().injectedConfigValue("greeting"));
        assertTrue(ConfigProvider.getConfig().getClass().getName().startsWith("io.vidocq.ravel."),
                ConfigProvider.getConfig().getClass().getName());
    }
}
