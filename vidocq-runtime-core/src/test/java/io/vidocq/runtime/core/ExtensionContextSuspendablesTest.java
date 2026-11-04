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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** {@link ExtensionContext#suspendables()}: the extensions that are {@link Suspendable}, in start order. */
class ExtensionContextSuspendablesTest {

    static class Plain implements VidocqExtension {
        @Override
        public String name() {
            return "plain";
        }
    }

    static final class Frozen extends Plain implements Suspendable {
        @Override
        public void suspend() {
        }

        @Override
        public void resume() {
        }
    }

    @Test
    void theApplicationLayerThenTheSuspendableExtensionsAreHandedOver_inStartOrder() {
        Frozen first = new Frozen();
        Frozen second = new Frozen();
        List<VidocqExtension> booted = List.of(first, new Plain(), second);
        VidocqBootstrap bootstrap = VidocqBootstrap.create().banner(BannerMode.OFF).extensions(booted).configure();

        List<Suspendable> suspendables = bootstrap.extensionContext().suspendables();

        assertEquals(3, suspendables.size());
        assertInstanceOf(AppLayerSuspendable.class, suspendables.get(0));
        assertSame(first, suspendables.get(1));
        assertSame(second, suspendables.get(2));
    }

    @Test
    void aBootstrapNotYetConfiguredHandsOverTheApplicationLayerOnly() {
        List<Suspendable> suspendables = VidocqBootstrap.create().extensionContext().suspendables();
        assertEquals(1, suspendables.size());
        assertInstanceOf(AppLayerSuspendable.class, suspendables.get(0));
    }
}
