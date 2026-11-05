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

import io.vidocq.runtime.spi.Suspendable;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.event.Checkpoint;
import io.vidocq.runtime.spi.event.Restore;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The application takes part in a process snapshot through two CDI events, {@link Checkpoint} and {@link Restore},
 * at {@link Suspendable#APPLICATION_PRIORITY}: after the server stopped taking requests and while the pool is still
 * open, then the other way round after the restore (Vidocq/vidocq#496).
 */
class ApplicationCheckpointEventsTest {

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    /** An extension that holds something a snapshot cannot carry, at the given priority. */
    static class Holder implements VidocqExtension, Suspendable {
        private final String name;
        private final int priority;

        Holder(String name, int priority) {
            this.name = name;
            this.priority = priority;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public void suspend() {
            EVENTS.add(name + " suspend");
        }

        @Override
        public void resume() {
            EVENTS.add(name + " resume");
        }
    }

    @BeforeEach
    void clear() {
        EVENTS.clear();
    }

    @Test
    void theApplicationLetsGoAfterTheServerAndBeforeThePool_andTakesBackInTheOtherOrder() {
        Holder pool = new Holder("pool", 200) {
            @Override
            public void beforeStart(VaubanContainerBuilder builder) {
                // The provider the Vauban indexer generated for this package's test beans, as an application's module
                // declares it with `provides`: the container creates the bean and calls its observers without reflection.
                builder.addComponentProvider(new _VaubanComponents()).addBeanClass(CheckpointObservers.class);
            }
        };
        Holder server = new Holder("server", 10_000);
        VidocqBootstrap bootstrap = VidocqBootstrap.create().banner(BannerMode.OFF)
                .extensions(List.of(pool, server)).configure().start();
        try {
            List<Suspendable> chain = bootstrap.extensionContext().suspendables();

            chain.reversed().forEach(Suspendable::suspend);
            chain.forEach(Suspendable::resume);

            assertEquals(List.of(
                    "server suspend", "application checkpoint 10", "application checkpoint 20", "pool suspend",
                    "pool resume", "application restore", "server resume"), EVENTS);
        } finally {
            bootstrap.shutdown();
        }
    }

    @Test
    void aBootThatDidNotStartFiresNothing() {
        List<Suspendable> chain = VidocqBootstrap.create().extensionContext().suspendables();

        chain.reversed().forEach(Suspendable::suspend);
        chain.forEach(Suspendable::resume);

        assertEquals(List.of(), EVENTS);
    }
}
