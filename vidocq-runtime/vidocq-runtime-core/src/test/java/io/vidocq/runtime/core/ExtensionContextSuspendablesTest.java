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
package io.vidocq.runtime.core;

import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.Suspendable;
import io.vidocq.runtime.spi.VidocqExtension;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link ExtensionContext#suspendables()}: the application layer, then the extensions that are {@link Suspendable}
 * in start order, with the application's own hooks at {@link Suspendable#APPLICATION_PRIORITY}.
 */
class ExtensionContextSuspendablesTest {

    static class Plain implements VidocqExtension {
        @Override
        public String name() {
            return "plain";
        }
    }

    static class Frozen extends Plain implements Suspendable {
        @Override
        public void suspend() {
            // A test double: only its place in the list matters, it holds nothing to release.
        }

        @Override
        public void resume() {
            // A test double: nothing was released, nothing to take back.
        }
    }

    static final class Ranked extends Frozen {
        private final int priority;

        Ranked(int priority) {
            this.priority = priority;
        }

        @Override
        public int priority() {
            return priority;
        }
    }

    @Test
    void theApplicationLayerThenTheSuspendableExtensionsAreHandedOver_inStartOrder() {
        Frozen first = new Frozen();
        Frozen second = new Frozen();
        List<VidocqExtension> booted = List.of(first, new Plain(), second);
        VidocqBootstrap bootstrap = VidocqBootstrap.create().banner(BannerMode.OFF).extensions(booted).configure();

        List<Suspendable> suspendables = bootstrap.extensionContext().suspendables();

        assertEquals(4, suspendables.size());
        assertInstanceOf(AppLayerSuspendable.class, suspendables.get(0));
        assertSame(first, suspendables.get(1));
        assertSame(second, suspendables.get(2));
        assertInstanceOf(ApplicationSuspendable.class, suspendables.get(3));
    }

    @Test
    void theApplicationHooksSitBetweenTheExtensionsBelowTheirPriorityAndThoseAtOrAboveIt() {
        Ranked telemetry = new Ranked(100);
        Ranked pool = new Ranked(200);
        Ranked atTheBoundary = new Ranked(Suspendable.APPLICATION_PRIORITY);
        Ranked server = new Ranked(10_000);
        List<VidocqExtension> booted = List.of(telemetry, pool, atTheBoundary, server);
        VidocqBootstrap bootstrap = VidocqBootstrap.create().banner(BannerMode.OFF).extensions(booted).configure();

        List<Suspendable> suspendables = bootstrap.extensionContext().suspendables();

        assertEquals(6, suspendables.size());
        assertInstanceOf(AppLayerSuspendable.class, suspendables.get(0));
        assertSame(telemetry, suspendables.get(1));
        assertSame(pool, suspendables.get(2));
        assertInstanceOf(ApplicationSuspendable.class, suspendables.get(3));
        assertSame(atTheBoundary, suspendables.get(4));
        assertSame(server, suspendables.get(5));
    }

    @Test
    void aBootstrapNotYetConfiguredHandsOverTheApplicationLayerAndTheApplicationHooks() {
        List<Suspendable> suspendables = VidocqBootstrap.create().extensionContext().suspendables();
        assertEquals(2, suspendables.size());
        assertInstanceOf(AppLayerSuspendable.class, suspendables.get(0));
        assertInstanceOf(ApplicationSuspendable.class, suspendables.get(1));
    }
}
