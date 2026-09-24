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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** A minimal {@link DevService} scripted to succeed or fail, recording how many times it was stopped. */
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
            return Map.of(id + ".url", "jdbc:x://h:1/" + id, id + ".password", "pw");
        }

        @Override
        public void stop() {
            stops++;
        }
    }
}
