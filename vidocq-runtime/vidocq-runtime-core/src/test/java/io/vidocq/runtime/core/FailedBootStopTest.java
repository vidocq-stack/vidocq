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
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A boot that fails in {@link VidocqBootstrap#start()} stops what it started (Vidocq/vidocq#145): every extension
 * whose {@code beforeStart} ran gets its {@code onStop}, in reverse order, the container is closed, and the
 * exception still goes through untouched.
 */
class FailedBootStopTest {

    private final List<String> events = new ArrayList<>();

    @Test
    void aFailingOnStartStopsEveryExtensionInReverseOrder() {
        IllegalStateException boom = new IllegalStateException("boom");
        VidocqExtension failing = new Probe("second", 20) {
            @Override
            public void onStart(ExtensionContext context) {
                throw boom;
            }
        };
        VidocqBootstrap bootstrap = bootstrap(new Probe("first", 10), failing, new Probe("third", 30)).configure();

        RuntimeException thrown = assertThrows(RuntimeException.class, bootstrap::start);

        assertSame(boom, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertEquals(List.of("onStart first", "onStop third", "onStop second", "onStop first"), stops());
    }

    @Test
    void aFailingBeforeStartStopsOnlyTheExtensionsWhoseBeforeStartRan() {
        IllegalStateException boom = new IllegalStateException("boom");
        VidocqExtension failing = new Probe("second", 20) {
            @Override
            public void beforeStart(VaubanContainerBuilder builder) {
                throw boom;
            }
        };
        VidocqBootstrap bootstrap = bootstrap(new Probe("first", 10), failing, new Probe("third", 30)).configure();

        assertSame(boom, assertThrows(IllegalStateException.class, bootstrap::start));

        assertEquals(List.of("onStop second", "onStop first"), stops());
    }

    @Test
    void aFailingConfigureStopsNothing() {
        VidocqExtension failing = new Probe("broken", 10) {
            @Override
            public void configure(VidocqConfiguration config) {
                throw new IllegalStateException("boom");
            }
        };
        VidocqBootstrap bootstrap = bootstrap(new Probe("first", 5), failing);

        assertThrows(IllegalStateException.class, bootstrap::configure);

        assertEquals(List.of(), stops());
    }

    @Test
    void anOnStopThatFailsIsLoggedAndTheOthersStillStop() {
        IllegalStateException boom = new IllegalStateException("boom");
        VidocqExtension failing = new Probe("second", 20) {
            @Override
            public void onStart(ExtensionContext context) {
                throw boom;
            }
        };
        VidocqExtension brokenStop = new Probe("third", 30) {
            @Override
            public void onStop() {
                super.onStop();
                throw new IllegalArgumentException("cannot stop");
            }
        };
        try (LogRecords records = new LogRecords(VidocqBootstrap.class.getName())) {
            VidocqBootstrap bootstrap = bootstrap(new Probe("first", 10), failing, brokenStop).configure();

            RuntimeException thrown = assertThrows(RuntimeException.class, bootstrap::start);

            assertSame(boom, thrown);
            assertEquals(0, thrown.getSuppressed().length);
            assertEquals(List.of("onStart first", "onStop third", "onStop second", "onStop first"), stops());
            List<String> errors = records.messages(VidocqBootstrap.class.getName(), Level.SEVERE);
            assertEquals(List.of("Error stopping extension: third"), errors);
        }
    }

    @Test
    void aFailingOnStartClosesTheContainer() {
        AtomicReference<VaubanContainer> built = new AtomicReference<>();
        VidocqExtension first = new Probe("first", 10) {
            @Override
            public void onStart(ExtensionContext context) {
                built.set(context.container());
            }
        };
        VidocqExtension failing = new Probe("second", 20) {
            @Override
            public void onStart(ExtensionContext context) {
                throw new IllegalStateException("boom");
            }
        };
        VidocqBootstrap bootstrap = bootstrap(first, failing).configure();

        assertThrows(IllegalStateException.class, bootstrap::start);

        assertFalse(built.get().isRunning(), "the container of a failed boot is closed");
    }

    @Test
    void aFailedBootIsStoppedOnce() {
        VidocqExtension failing = new Probe("second", 20) {
            @Override
            public void onStart(ExtensionContext context) {
                throw new IllegalStateException("boom");
            }
        };
        VidocqBootstrap bootstrap = bootstrap(new Probe("first", 10), failing).configure();
        assertThrows(IllegalStateException.class, bootstrap::start);
        events.clear();

        bootstrap.shutdown();

        assertEquals(List.of(), events, "shutdown after a failed boot stops nothing again");
        assertTrue(bootstrap.awaitShutdown(0), "a failed boot counts as stopped");
    }

    // ------------------------------------------------------------------------------------------ helpers

    private VidocqBootstrap bootstrap(VidocqExtension... extensions) {
        return VidocqBootstrap.create().banner(BannerMode.OFF).extensions(List.of(extensions));
    }

    /** The events that tell what a failed boot stopped: the successful onStart calls and every onStop. */
    private List<String> stops() {
        return events.stream().filter(e -> e.startsWith("onStop ") || e.startsWith("onStart ")).toList();
    }

    /** An extension that records its onStart and onStop calls into {@link #events}. */
    private class Probe implements VidocqExtension {
        private final String name;
        private final int priority;

        Probe(String name, int priority) {
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
        public Set<String> configKeys() {
            return Set.of();
        }

        @Override
        public void onStart(ExtensionContext context) {
            events.add("onStart " + name);
        }

        @Override
        public void onStop() {
            events.add("onStop " + name);
        }
    }
}
