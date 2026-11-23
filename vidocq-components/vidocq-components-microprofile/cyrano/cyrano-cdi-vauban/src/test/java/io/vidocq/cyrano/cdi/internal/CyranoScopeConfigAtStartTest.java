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
package io.vidocq.cyrano.cdi.internal;

import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.Bean;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MicroProfile Rest Client 4.0 §"Support for MicroProfile Config": {@code <interface>/mp-rest/scope} sets the scope
 * of the client bean. The start's configuration decides it: a key set when the application starts applies, whether
 * the start runs the extensions or reads the record a build froze (Vidocq/vauban#288).
 *
 * <p>BUG-20261123-47: {@link CyranoRestClientCdiExtension} reads the key in its {@code @Enhancement} phase, so a
 * frozen build keeps the value of the build machine, and a key set at start is ignored. The frozen case is disabled
 * until the maintainer picks a design (options in {@code BUG.md}); it fails on the current code.</p>
 */
class CyranoScopeConfigAtStartTest {

    private static final String SCOPE_KEY = CyranoRestClientCdiIntegrationTest.PingApi.class.getName() + "/mp-rest/scope";

    @TempDir
    Path output;

    @AfterEach
    void clearConfig() {
        System.clearProperty(SCOPE_KEY);
    }

    private VaubanContainerBuilder deployment() {
        return VaubanContainer.builder()
                .classLoader(outputLoader())
                .addBeanClass(CyranoRestClientCdiExtension.class)
                .addBeanClass(CyranoRestClientCdiIntegrationTest.PingApi.class);
    }

    /** The loader of a start: the build's output first, where a record is, then the classes of this test. */
    private ClassLoader outputLoader() {
        try {
            return new URLClassLoader(new URL[] {output.toUri().toURL()}, getClass().getClassLoader());
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Class<?> clientScope(VaubanContainer container) {
        Set<Bean<?>> beans = container.getBeanManager()
                .getBeans(CyranoRestClientCdiIntegrationTest.PingApi.class, RestClient.LITERAL);
        assertEquals(1, beans.size(), beans.toString());
        return beans.iterator().next().getScope();
    }

    @Test
    void scopeKeySetAtStartAppliesWhenTheStartRunsTheExtension() {
        System.setProperty(SCOPE_KEY, ApplicationScoped.class.getName());
        try (var container = deployment().build()) {
            assertEquals(ApplicationScoped.class, clientScope(container));
        }
    }

    @Test
    @Disabled("BUG-20261123-47 (OPEN): the scope key is read at build; the design waits for the maintainer")
    void scopeKeySetAtStartAppliesWhenTheStartReadsAFrozenRecord() {
        var outcome = deployment().freezeExtensions(output);
        assertTrue(outcome.frozen(), outcome.reason());
        System.setProperty(SCOPE_KEY, ApplicationScoped.class.getName());
        try (var container = deployment().build()) {
            assertTrue(container.extensionsAtStart().frozen(), "frozen");
            assertEquals(ApplicationScoped.class, clientScope(container));
        }
    }
}
