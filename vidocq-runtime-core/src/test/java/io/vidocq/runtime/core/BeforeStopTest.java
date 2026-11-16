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
import io.vidocq.runtime.spi.VidocqExtension;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The task the dev reload loop gives a boot runs when that boot stops, before any extension stops: the application
 * still serves requests, so a {@code -dev} companion can end what it keeps open (an event stream) before the servlet
 * container waits for the requests in service.
 */
class BeforeStopTest {

    private final List<String> events = new CopyOnWriteArrayList<>();

    @Test
    void theTaskRunsBeforeTheExtensionsStop() {
        VidocqBootstrap bootstrap = bootstrap().beforeStop(() -> events.add("before stop")).configure().start();

        bootstrap.shutdown();

        assertEquals(List.of("onStart probe", "before stop", "onStop probe"), events);
    }

    @Test
    void aTaskThatFailsIsLoggedAndTheStopGoesOn() {
        try (LogRecords records = new LogRecords(VidocqBootstrap.class.getName())) {
            VidocqBootstrap bootstrap = bootstrap().beforeStop(() -> {
                throw new IllegalStateException("no stream");
            }).configure().start();

            bootstrap.shutdown();

            assertEquals(List.of("onStart probe", "onStop probe"), events);
            assertEquals(List.of("Vidocq - the task before the stop failed: java.lang.IllegalStateException: no stream"),
                    records.messages(VidocqBootstrap.class.getName(), Level.WARNING));
        }
    }

    private VidocqBootstrap bootstrap() {
        return VidocqBootstrap.create().banner(BannerMode.OFF).extensions(List.of(new Probe()));
    }

    private final class Probe implements VidocqExtension {

        @Override
        public String name() {
            return "probe";
        }

        @Override
        public Set<String> configKeys() {
            return Set.of();
        }

        @Override
        public void onStart(ExtensionContext context) {
            events.add("onStart probe");
        }

        @Override
        public void onStop() {
            events.add("onStop probe");
        }
    }
}
