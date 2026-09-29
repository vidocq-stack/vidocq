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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevServiceManagerTest {

    private static final System.Logger LOG = System.getLogger("DevServiceManagerTest");

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

    /**
     * The application cannot tell a dev-service datasource from a hand-set {@code -D}: the manager keeps which
     * provider supplied each key, so the goal can say so. A key two providers supply is the later one's, as its value
     * is.
     */
    @Test
    void recordsWhichProviderSuppliedEachKey() throws Exception {
        List<String> events = new ArrayList<>();
        FakeDevService postgres = new FakeDevService("postgres", 100, true,
                Map.of("vidocq.pool.url", "jdbc:postgresql://localhost:5440/vidocq", "shared", "1"),
                false, null, events);
        FakeDevService keycloak = new FakeDevService("keycloak", 200, true,
                Map.of("mp.jwt.verify.issuer", "http://localhost:8180/realms/vidocq", "shared", "2"),
                false, null, events);
        FakeDevService skipped = new FakeDevService("skipped", 300, false, Map.of("skipped.key", "3"),
                false, null, events);

        DevServiceManager mgr = DevServiceManager.start(List.of(keycloak, skipped, postgres), ctx(), LOG);

        assertEquals(Map.of("vidocq.pool.url", "postgres",
                "mp.jwt.verify.issuer", "keycloak",
                "shared", "keycloak"), mgr.providers());
        assertEquals("2", mgr.collectedProperties().get("shared"), "the value and its provider agree");
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

        DevServicesException ex = assertThrows(DevServicesException.class,
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
        assertTrue(mgr.providers().isEmpty());
        mgr.close();
        assertFalse(mgr.collectedProperties().containsKey("anything"));
    }

    /**
     * {@link DevServiceManager#states()} must report each provider's own output, in start order — not the
     * aggregated {@link DevServiceManager#collectedProperties()}, where a later provider's value for a shared
     * key would otherwise hide the earlier provider's key entirely.
     */
    @Test
    void statesReflectExactlyWhatEachProviderReturnedInStartOrder() throws Exception {
        List<String> events = new ArrayList<>();
        FakeDevService a = new FakeDevService("a", 100, true,
                Map.of("shared", "from-a", "a.only", "1"), false, null, events);
        FakeDevService b = new FakeDevService("b", 200, true,
                Map.of("shared", "from-b", "b.only", "2"), false, null, events);

        DevServiceManager mgr = DevServiceManager.start(List.of(b, a), ctx(), LOG);

        List<DevServiceState> states = mgr.states();
        assertEquals(2, states.size());
        assertEquals("a", states.get(0).id(), "states() is in start order, not registration order");
        assertEquals(List.of("a.only", "shared"), states.get(0).injectedKeys());
        assertEquals("b", states.get(1).id());
        assertEquals(List.of("b.only", "shared"), states.get(1).injectedKeys(),
                "b's own output, not a's — the shared key must not bleed across providers");
    }

    @Test
    void describeThatThrowsFallsBackToAMinimalStateAndLogsAWarning() throws Exception {
        List<String> events = new ArrayList<>();
        FakeDevService broken = new FakeDevService("broken", 100, true,
                Map.of("broken.key", "1"), false, null, events, true);
        CapturingLogger log = new CapturingLogger();

        DevServiceManager mgr = DevServiceManager.start(List.of(broken), ctx(), log);

        assertEquals(List.of(DevServiceState.minimal("broken", List.of("broken.key"))), mgr.states());
        assertTrue(log.lines.stream().anyMatch(
                line -> line.contains("WARNING") && line.contains("broken") && line.contains("IllegalStateException")),
                log.lines.toString());
        assertFalse(log.lines.stream().anyMatch(line -> line.contains("boom-describe")),
                "the exception's message may carry a secret and must never be logged: " + log.lines);
    }

    @Test
    void anErrorFromAProviderRollsBackTheStartedOnesAndIsRethrown() {
        List<String> events = new ArrayList<>();
        FakeDevService a = new FakeDevService("a", 100, true, Map.of("a.key", "1"), false, null, events);
        DevService b = new Scripted("b") {
            @Override
            public Map<String, String> start(DevServiceContext ctx) {
                throw new NoClassDefFoundError("org/testcontainers/Missing");
            }
        };

        NoClassDefFoundError e = assertThrows(NoClassDefFoundError.class,
                () -> DevServiceManager.start(List.of(a, b), ctx(), LOG));

        assertEquals("org/testcontainers/Missing", e.getMessage());
        assertEquals(1, a.stopCount, "the provider already started is stopped");
    }

    @Test
    void aStopThatThrowsIsLoggedAndTheOthersAreStillStopped() throws Exception {
        List<String> events = new ArrayList<>();
        FakeDevService a = new FakeDevService("a", 100, true, Map.of(), false, null, events);
        DevService b = new Scripted("b") {
            @Override
            public void stop() {
                throw new IllegalStateException("boom-stop-secret");
            }
        };
        CapturingLogger log = new CapturingLogger();

        DevServiceManager mgr = DevServiceManager.start(List.of(a, b), ctx(), log);
        mgr.close();

        assertEquals(1, a.stopCount);
        assertEquals(1, log.lines.stream().filter(line -> line.startsWith("WARNING") && line.contains("b stop()")
                && line.contains("IllegalStateException")).count(), log.lines.toString());
        assertFalse(log.lines.stream().anyMatch(line -> line.contains("boom-stop-secret")), log.lines.toString());
    }

    /*
     * CapturingLogger renders a line through MessageFormat, which drops the single quotes around a provider's id:
     * "DevService 'postgres' not started: …" is captured as "INFO DevService postgres not started: …".
     */

    @Test
    void aProviderThatDoesNotApplyAndSaysWhyIsLoggedAndRecorded() throws Exception {
        DevService h2 = new Scripted("postgres") {
            @Override public boolean appliesWhen(DevServiceContext ctx) { return false; }
            @Override public String skipReason(DevServiceContext ctx) {
                return "vidocq.pool.url is jdbc:h2, not PostgreSQL";
            }
        };
        CapturingLogger log = new CapturingLogger();

        DevServiceManager mgr = DevServiceManager.start(List.of(h2), ctx(), log);

        assertEquals(List.of(new DevServiceManager.Skipped("postgres", "vidocq.pool.url is jdbc:h2, not PostgreSQL")),
                mgr.skipped());
        assertTrue(log.lines.stream().anyMatch(line -> line.startsWith("INFO")
                && line.contains("postgres not started: vidocq.pool.url is jdbc:h2, not PostgreSQL")), log.lines.toString());
        assertTrue(mgr.collectedProperties().isEmpty());
    }

    @Test
    void withoutAReasonTheOldLineIsLoggedAndNothingIsRecorded() throws Exception {
        List<String> events = new ArrayList<>();
        FakeDevService configured = new FakeDevService("keycloak", 100, false, Map.of(), false, null, events);
        CapturingLogger log = new CapturingLogger();

        DevServiceManager mgr = DevServiceManager.start(List.of(configured), ctx(), log);

        assertEquals(List.of(), mgr.skipped());
        assertTrue(log.lines.stream().anyMatch(line -> line.startsWith("INFO")
                && line.contains("keycloak skipped (already configured)")), log.lines.toString());
    }

    /** Review Focus: a reason is one line with no credentials; a skipReason that throws is none, and never fatal. */
    @Test
    void aReasonIsOneLineWithoutCredentialsAndAThrowingOneIsNone() throws Exception {
        DevService leaky = new Scripted("leaky") {
            @Override public boolean appliesWhen(DevServiceContext ctx) { return false; }
            @Override public String skipReason(DevServiceContext ctx) {
                return "  url jdbc:mysql://admin:hunter2@db/app\n   refused  ";
            }
        };
        DevService broken = new Scripted("broken") {
            @Override public boolean appliesWhen(DevServiceContext ctx) { return false; }
            @Override public String skipReason(DevServiceContext ctx) {
                throw new IllegalStateException("secret-in-message");
            }
        };
        CapturingLogger log = new CapturingLogger();

        DevServiceManager mgr = DevServiceManager.start(List.of(leaky, broken), ctx(), log);

        assertEquals(List.of(new DevServiceManager.Skipped("leaky", "url jdbc:mysql://***@db/app refused")),
                mgr.skipped());
        assertFalse(log.lines.toString().contains("hunter2"), log.lines.toString());
        assertFalse(log.lines.toString().contains("secret-in-message"), log.lines.toString());
        assertTrue(log.lines.stream().anyMatch(line -> line.startsWith("WARNING") && line.contains("broken")
                && line.contains("IllegalStateException")), log.lines.toString());
        assertTrue(log.lines.stream().anyMatch(line -> line.contains("broken skipped (already configured)")),
                log.lines.toString());
    }

    /** A provider that applies, starts with no output and stops quietly, for a test to override one step of. */
    private static class Scripted implements DevService {
        private final String id;

        Scripted(String id) {
            this.id = id;
        }

        @Override public String id() { return id; }
        @Override public int order() { return 200; }
        @Override public boolean appliesWhen(DevServiceContext ctx) { return true; }
        @Override public Map<String, String> start(DevServiceContext ctx) { return Map.of(); }
        @Override public void stop() {}
    }

    /** Captures every line logged through it, as {@code "LEVEL message"}. */
    private static final class CapturingLogger implements System.Logger {
        final List<String> lines = new ArrayList<>();

        @Override public String getName() { return "CapturingLogger"; }
        @Override public boolean isLoggable(Level level) { return true; }

        @Override
        public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
            lines.add(level + " " + msg);
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            lines.add(level + " " + java.text.MessageFormat.format(format, params));
        }
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
        private final boolean describeThrows;
        private String sawValue;
        private int stopCount;

        FakeDevService(String id, int order, boolean applies, Map<String, String> output,
                       boolean fail, String readKey, List<String> events) {
            this(id, order, applies, output, fail, readKey, events, false);
        }

        FakeDevService(String id, int order, boolean applies, Map<String, String> output,
                       boolean fail, String readKey, List<String> events, boolean describeThrows) {
            this.id = id;
            this.order = order;
            this.applies = applies;
            this.output = output;
            this.fail = fail;
            this.readKey = readKey;
            this.events = events;
            this.describeThrows = describeThrows;
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
        public DevServiceState describe(Map<String, String> injected) {
            if (describeThrows) {
                // The message deliberately looks like a secret, to prove the manager never logs it.
                throw new IllegalStateException("boom-describe-" + id);
            }
            return DevService.super.describe(injected);
        }

        @Override
        public void stop() {
            stopCount++;
            events.add("stop:" + id);
        }
    }
}
