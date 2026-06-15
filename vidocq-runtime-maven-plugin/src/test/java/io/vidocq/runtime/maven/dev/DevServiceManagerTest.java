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
package io.vidocq.runtime.maven.dev;

import io.vidocq.runtime.devservices.spi.DevService;
import io.vidocq.runtime.devservices.spi.DevServiceContext;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevServiceManagerTest {

    private static final Log LOG = new SystemStreamLog();

    private static DefaultDevServiceContext ctx() {
        return new DefaultDevServiceContext(Path.of("."), Map.of());
    }

    @Test
    void startsProvidersByAscendingOrder() throws Exception {
        List<String> events = new ArrayList<>();
        FakeDevService b = new FakeDevService("b", 200, true, Map.of(), false, null, events);
        FakeDevService a = new FakeDevService("a", 100, true, Map.of(), false, null, events);

        DevServiceManager.start(List.of(b, a), ctx(), LOG);

        assertEquals(List.of("start:a", "start:b"), events);
    }

    @Test
    void collectsApplicablePropertiesAndSkipsTheRest() throws Exception {
        List<String> events = new ArrayList<>();
        FakeDevService a = new FakeDevService("a", 100, true, Map.of("a.key", "1"), false, null, events);
        FakeDevService b = new FakeDevService("b", 200, false, Map.of("b.key", "2"), false, null, events);

        DevServiceManager mgr = DevServiceManager.start(List.of(a, b), ctx(), LOG);

        assertEquals(Map.of("a.key", "1"), mgr.collectedProperties());
        assertEquals(List.of("start:a"), events); // b skipped, never started
    }

    @Test
    void laterProviderSeesEarlierProviderOutput() throws Exception {
        List<String> events = new ArrayList<>();
        FakeDevService a = new FakeDevService("a", 100, true, Map.of("shared", "hello"), false, null, events);
        FakeDevService b = new FakeDevService("b", 200, true, Map.of(), false, "shared", events);

        DevServiceManager.start(List.of(a, b), ctx(), LOG);

        assertEquals("hello", b.sawValue);
    }

    @Test
    void failureRollsBackStartedProvidersInReverseAndThrows() {
        List<String> events = new ArrayList<>();
        FakeDevService a = new FakeDevService("a", 100, true, Map.of("a.key", "1"), false, null, events);
        FakeDevService b = new FakeDevService("b", 200, true, Map.of(), true, null, events);

        MojoExecutionException ex = assertThrows(MojoExecutionException.class,
                () -> DevServiceManager.start(List.of(a, b), ctx(), LOG));

        assertTrue(ex.getMessage().contains("'b'"));
        assertTrue(ex.getMessage().contains("vidocq.dev.devServices=false"));
        assertEquals(List.of("start:a", "start:b", "stop:a"), events); // a rolled back, b never registered
        assertEquals(1, a.stopCount);
        assertEquals(0, b.stopCount);
    }

    @Test
    void closeStopsInReverseOrderAndIsIdempotent() throws Exception {
        List<String> events = new ArrayList<>();
        FakeDevService a = new FakeDevService("a", 100, true, Map.of(), false, null, events);
        FakeDevService b = new FakeDevService("b", 200, true, Map.of(), false, null, events);

        DevServiceManager mgr = DevServiceManager.start(List.of(a, b), ctx(), LOG);
        mgr.close();
        mgr.close(); // second close must be a no-op

        assertEquals(List.of("start:a", "start:b", "stop:b", "stop:a"), events);
        assertEquals(1, a.stopCount);
        assertEquals(1, b.stopCount);
    }

    @Test
    void noProvidersIsAnEmptyButValidRun() throws Exception {
        DevServiceManager mgr = DevServiceManager.start(List.of(), ctx(), LOG);
        assertTrue(mgr.collectedProperties().isEmpty());
        mgr.close();
        assertFalse(mgr.collectedProperties().containsKey("anything"));
    }

    /** A scripted DevService that records its lifecycle into a shared event log. */
    private static final class FakeDevService implements DevService {
        private final String id;
        private final int order;
        private final boolean applies;
        private final Map<String, String> output;
        private final boolean fail;
        private final String readKey;
        private final List<String> events;
        private String sawValue;
        private int stopCount;

        FakeDevService(String id, int order, boolean applies, Map<String, String> output,
                       boolean fail, String readKey, List<String> events) {
            this.id = id;
            this.order = order;
            this.applies = applies;
            this.output = output;
            this.fail = fail;
            this.readKey = readKey;
            this.events = events;
        }

        @Override public String id() { return id; }
        @Override public int order() { return order; }
        @Override public boolean appliesWhen(DevServiceContext ctx) { return applies; }

        @Override
        public Map<String, String> start(DevServiceContext ctx) {
            if (readKey != null) {
                sawValue = ctx.property(readKey).orElse(null);
            }
            events.add("start:" + id);
            if (fail) {
                throw new IllegalStateException("boom-" + id);
            }
            return output;
        }

        @Override
        public void stop() {
            stopCount++;
            events.add("stop:" + id);
        }
    }
}
