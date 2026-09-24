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
package io.vidocq.runtime.devservices.junit;

import io.vidocq.runtime.devservices.host.DevServicesSession;
import io.vidocq.runtime.devservices.host.StateFile;
import io.vidocq.runtime.devservices.spi.DevService;
import io.vidocq.runtime.devservices.spi.DevServiceContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link DevServicesSessionListener} directly, through its package-private constructor, with a fake
 * opener backed by {@link DevServicesSession#forTesting} (Task 4) rather than real {@code ServiceLoader}
 * discovery or a real {@code LauncherSession}. Both listener methods ignore their {@code LauncherSession}
 * argument, so tests pass {@code null} for it.
 */
class DevServicesSessionListenerTest {

    private static final System.Logger LOG = System.getLogger("test");

    @AfterEach
    void restoreSystemProperties() {
        System.clearProperty("a.url");
        System.clearProperty("vidocq.dev.provided.a.url");
        System.clearProperty(StateFile.PROPERTY);
        System.clearProperty("vidocq.dev.devServices");
        System.clearProperty("basedir");
    }

    @Test
    void openInjectsPropertiesAndWritesTheStateFileThenCloseStopsTheProviderOnce(@TempDir Path basedir) {
        System.setProperty("basedir", basedir.toString());
        Fake fake = new Fake("a", false);
        AtomicInteger calls = new AtomicInteger();
        DevServicesSessionListener listener = new DevServicesSessionListener(dir -> {
            calls.incrementAndGet();
            try {
                return DevServicesSession.forTesting("test", dir, List.of(fake), LOG);
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        });

        listener.launcherSessionOpened(null);

        assertEquals(1, calls.get());
        assertEquals("jdbc:x://h:1/a", System.getProperty("a.url"));
        String stateFile = System.getProperty(StateFile.PROPERTY);
        assertTrue(stateFile != null && Files.exists(Path.of(stateFile)), "state file exists: " + stateFile);

        listener.launcherSessionClosed(null);

        assertEquals(1, fake.stops, "stopped exactly once");
    }

    @Test
    void aSystemPropertyAlreadySetBeforeOpeningKeepsItsValue(@TempDir Path basedir) {
        System.setProperty("basedir", basedir.toString());
        System.setProperty("a.url", "already-set");
        Fake fake = new Fake("a", false);
        DevServicesSessionListener listener = new DevServicesSessionListener(
                dir -> forTesting(dir, fake));

        listener.launcherSessionOpened(null);

        assertEquals("already-set", System.getProperty("a.url"));

        listener.launcherSessionClosed(null);
    }

    @Test
    void devServicesOffSkipsOpeningEntirely(@TempDir Path basedir) {
        System.setProperty("basedir", basedir.toString());
        System.setProperty("vidocq.dev.devServices", "false");
        AtomicInteger calls = new AtomicInteger();
        DevServicesSessionListener listener =
                new DevServicesSessionListener(dir -> {
                    calls.incrementAndGet();
                    return forTesting(dir, new Fake("a", false));
                });

        listener.launcherSessionOpened(null);

        assertEquals(0, calls.get(), "opener never called");

        // No session was opened, so closing must be a harmless no-op.
        listener.launcherSessionClosed(null);
    }

    private static DevServicesSession forTesting(Path dir, Fake fake) {
        try {
            return DevServicesSession.forTesting("test", dir, List.of(fake), LOG);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** A minimal {@link DevService} scripted to succeed, recording how many times it was stopped. */
    private static final class Fake implements DevService {
        final String id;
        final boolean fail;
        int stops;

        Fake(String id, boolean fail) {
            this.id = id;
            this.fail = fail;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public boolean appliesWhen(DevServiceContext ctx) {
            return true;
        }

        @Override
        public Map<String, String> start(DevServiceContext ctx) throws Exception {
            if (fail) {
                throw new IllegalStateException("no docker");
            }
            return Map.of(id + ".url", "jdbc:x://h:1/" + id);
        }

        @Override
        public void stop() {
            stops++;
        }
    }
}
