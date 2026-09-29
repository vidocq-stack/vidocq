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
package io.vidocq.runtime.devservices.host;

import io.vidocq.runtime.devservices.spi.DevService;
import io.vidocq.runtime.devservices.spi.DevServiceContext;
import io.vidocq.runtime.devservices.spi.DevServiceState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevServicesSessionTest {

    private static final System.Logger LOG = System.getLogger("test");
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-24T10:00:00Z"), ZoneOffset.UTC);

    private static DefaultDevServiceContext ctx(Path basedir) {
        return new DefaultDevServiceContext(basedir, Map.of());
    }

    @Test
    void openWritesARunningStateAndCloseAStoppedOneOnce(@TempDir Path basedir) throws Exception {
        Fake a = new Fake("a", false);
        DevServicesSession s = DevServicesSession.open("vidocq:run", basedir, List.of(a), ctx(basedir), LOG, CLOCK);
        String running = Files.readString(s.stateFile());
        assertTrue(running.contains("\"state\":\"running\""), running);
        assertTrue(running.contains("\"host\":\"vidocq:run\""), running);
        assertEquals("jdbc:x://h:1/a", s.injected().get("a.url"));

        s.close();
        s.close();

        assertEquals(1, a.stops, "stopped exactly once");
        assertTrue(Files.readString(s.stateFile()).contains("\"state\":\"stopped\""));
    }

    @Test
    void aProviderThatFailsStopsTheOnesAlreadyStartedAndNamesItself(@TempDir Path basedir) {
        Fake a = new Fake("a", false);
        Fake b = new Fake("b", true);
        DevServicesException e = assertThrows(DevServicesException.class,
                () -> DevServicesSession.open("test", basedir, List.of(a, b), ctx(basedir), LOG, CLOCK));
        assertTrue(e.getMessage().contains("'b'"), e.getMessage());
        assertTrue(e.getMessage().contains("vidocq.dev.devServices=false"), e.getMessage());
        assertEquals(1, a.stops);
    }

    @Test
    void foldKeepsAnExplicitValueUnmarkedAndMarksATakenOne() {
        Map<String, String> target = new LinkedHashMap<>();
        target.put("vidocq.pool.password", "hand-set");
        DevServicesSession.fold(
                Map.of("vidocq.pool.password", "pw", "vidocq.pool.url", "jdbc:x://h:1/a"),
                Map.of("vidocq.pool.password", "postgres", "vidocq.pool.url", "postgres"),
                target);
        assertEquals("hand-set", target.get("vidocq.pool.password"));
        assertNull(target.get("vidocq.dev.provided.vidocq.pool.password"), "a kept value is not marked");
        assertEquals("jdbc:x://h:1/a", target.get("vidocq.pool.url"));
        assertEquals("postgres", target.get("vidocq.dev.provided.vidocq.pool.url"), "a taken value is marked");
    }

    @Test
    void foldIntoSystemPropertiesWorksOnAPropertiesTarget(@TempDir Path basedir) throws Exception {
        Properties target = new Properties();
        target.setProperty("a.password", "hand-set");
        try (DevServicesSession s = DevServicesSession.open("test", basedir, List.of(new Fake("a", false)),
                ctx(basedir), LOG, CLOCK)) {
            s.foldInto(target);
        }
        assertEquals("hand-set", target.getProperty("a.password"));
        assertNull(target.getProperty("vidocq.dev.provided.a.password"));
        assertEquals("a", target.getProperty("vidocq.dev.provided.a.url"));
    }

    @Test
    void anUnwritableStateFileStopsTheProvidersAndThrows(@TempDir Path basedir) throws Exception {
        Files.writeString(basedir.resolve("target"), "a plain file where the target directory should be");
        Fake a = new Fake("a", false);
        DevServicesException e = assertThrows(DevServicesException.class,
                () -> DevServicesSession.open("test", basedir, List.of(a), ctx(basedir), LOG, CLOCK));
        assertTrue(e.getMessage().contains("state file"), e.getMessage());
        assertEquals(1, a.stops, "stopped exactly once");
    }

    @Test
    void anErrorWhileWritingTheStateStopsTheProvidersAndIsRethrown(@TempDir Path basedir) {
        Fake a = new Fake("a", false);
        a.describe = n -> {
            throw new AssertionError("describe blew up");
        };
        AssertionError e = assertThrows(AssertionError.class,
                () -> DevServicesSession.open("test", basedir, List.of(a), ctx(basedir), LOG, CLOCK));
        assertEquals("describe blew up", e.getMessage());
        assertEquals(1, a.stops, "stopped exactly once");
    }

    @Test
    void closeNeverThrowsWhenTheStoppedStateCannotBeRendered(@TempDir Path basedir) throws Exception {
        Fake a = new Fake("a", false);
        // Fine at open, then a null state (a broken provider) when close re-describes it.
        a.describe = n -> n == 1 ? DevServiceState.minimal("a", List.of("a.url")) : null;
        DevServicesSession s = DevServicesSession.open("test", basedir, List.of(a), ctx(basedir), LOG, CLOCK);
        assertDoesNotThrow(s::close);
        assertEquals(1, a.stops);
    }

    @Test
    void bothStateFilesKeepWhyAProviderDidNotStart(@TempDir Path basedir) throws Exception {
        DevService h2 = new DevService() {
            @Override public String id() { return "postgres"; }
            @Override public boolean appliesWhen(DevServiceContext ctx) { return false; }
            @Override public String skipReason(DevServiceContext ctx) {
                return "vidocq.pool.url is jdbc:h2, not PostgreSQL";
            }
            @Override public Map<String, String> start(DevServiceContext ctx) { throw new AssertionError("started"); }
            @Override public void stop() { }
        };
        String skipped = "\"skipped\":[{\"id\":\"postgres\",\"reason\":\"vidocq.pool.url is jdbc:h2, not PostgreSQL\"}]";

        DevServicesSession s = DevServicesSession.open("vidocq:dev", basedir, List.of(h2), ctx(basedir), LOG, CLOCK);
        String running = Files.readString(s.stateFile());
        s.close();
        String stopped = Files.readString(s.stateFile());

        assertTrue(running.contains(skipped), running);
        assertTrue(stopped.contains(skipped), stopped);
    }

    /** A minimal {@link DevService} scripted to succeed or fail, recording how many times it was stopped. */
    private static final class Fake implements DevService {
        final String id;
        final boolean fail;
        int stops;
        /** {@link #describe}'s answer by call number (1 for the first call); the SPI default when unset. */
        IntFunction<DevServiceState> describe;
        private int describes;

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
            return Map.of(id + ".url", "jdbc:x://h:1/" + id, id + ".password", "pw");
        }

        @Override
        public DevServiceState describe(Map<String, String> injected) {
            describes++;
            return describe == null ? DevService.super.describe(injected) : describe.apply(describes);
        }

        @Override
        public void stop() {
            stops++;
        }
    }
}
