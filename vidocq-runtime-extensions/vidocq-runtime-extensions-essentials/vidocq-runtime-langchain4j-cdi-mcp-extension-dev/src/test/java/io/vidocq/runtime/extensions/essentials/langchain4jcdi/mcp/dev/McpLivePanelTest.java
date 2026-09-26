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
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import dev.langchain4j.cdi.mcp.server.transport.McpNotificationBroadcaster;
import dev.langchain4j.cdi.mcp.server.transport.McpResourceSubscriptionManager;
import dev.langchain4j.cdi.mcp.server.transport.McpRootsManager;
import dev.langchain4j.cdi.mcp.server.transport.McpServerRequestManager;
import dev.langchain4j.cdi.mcp.server.transport.McpSessionManager;
import dev.langchain4j.cdi.mcp.server.transport.McpSubscriptionRegistry;
import io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.live.McpInspection;
import io.vidocq.runtime.spi.devconsole.Chart;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.devconsole.Series;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U5 of Vidocq/vidocq#94, moved here by Vidocq/vidocq#143: the {@code mcp} panel shown live. What it samples before
 * the server has served anything, that sampling creates nothing — the test that actually protects the contract —
 * how long a sample takes, that a stopped panel writes nothing, and that a twin server leaves the panel inert.
 */
class McpLivePanelTest {

    /** The MCP server beans of a container, with everything their injection points need. */
    private static final Class<?>[] SERVER_BEANS = {
        McpSessionManager.class, McpNotificationBroadcaster.class, McpSubscriptionRegistry.class,
        McpServerRequestManager.class, McpResourceSubscriptionManager.class, McpRootsManager.class
    };

    /** The five gauges, then the three invoker values: what one sample of a deployed server writes. */
    private static final List<String> ALL_KEYS = List.of(
            "sessions", "streams", "listens", "pending", "invoker.methods", "invoker.matches", "invoker.misses");

    private final McpLivePanel panel = new McpLivePanel();

    @AfterEach
    void stop() {
        panel.stop();
    }

    @Test
    void itMakesTheMcpSectionLiveWithItsCharts() {
        assertEquals("mcp", panel.id());
        assertEquals(List.of("connections", "server-requests"), panel.charts().stream().map(Chart::id).toList());
        Chart connections = panel.charts().get(0);
        assertEquals("Connections", connections.title());
        assertEquals(List.of(Series.area("sessions"), Series.line("streams"), Series.line("listens")),
                connections.series());
        Chart requests = panel.charts().get(1);
        assertEquals("Server-to-client requests", requests.title());
        assertEquals(List.of(Series.line("pending")), requests.series());
        // The invoker counters get no chart: they grow once per method, never with the traffic.
        panel.charts().forEach(chart -> chart.series()
                .forEach(series -> assertFalse(series.key().startsWith("invoker."), series.key())));
        panel.charts().forEach(chart -> PanelSample.requireKey(chart.id()));
    }

    @Test
    void beforeAnyRequestSessionsIsAbsentWithItsReason() {
        try (VaubanContainer container = started()) {
            RecordingSample sample = new RecordingSample();

            panel.sample(sample);

            assertEquals(ALL_KEYS, List.copyOf(sample.keys()));
            assertEquals("absent", sample.kind("sessions"));
            assertEquals("no request served yet", sample.text("sessions"));
            // Never a zero, which would claim a measure the panel does not have.
            for (String key : ALL_KEYS) {
                assertEquals("absent", sample.kind(key), key);
                assertNotNull(sample.text(key), key + " is absent without saying why");
            }
            // The beans were resolved: these three are absent because no instance exists, not because the
            // container holds none — otherwise the "nothing was created" test below would prove nothing.
            assertEquals("not created yet", sample.text("streams"));
            assertEquals("not created yet", sample.text("listens"));
            assertEquals("not created yet", sample.text("pending"));
            // No CDI 4.1 invoker provider in a plain container: the server would invoke by reflection.
            assertEquals("reflection", sample.text("invoker.methods"));
            assertEquals("reflection", sample.text("invoker.matches"));
            assertEquals("reflection", sample.text("invoker.misses"));
        }
    }

    /**
     * The other half of the contract: once the application itself has created a bean, the panel reads its number
     * from the very instance, so the absences above come from {@code Context.get} and not from a bean it failed to
     * resolve. {@link McpSubscriptionRegistry} is the one to force: it starts no thread.
     */
    @Test
    void aBeanTheApplicationCreatedGivesItsNumber() {
        try (VaubanContainer container = started()) {
            BeanManager beans = container.getBeanManager();
            Bean<?> bean = beans.resolve(beans.getBeans(McpSubscriptionRegistry.class));
            McpSubscriptionRegistry registry = (McpSubscriptionRegistry)
                    beans.getReference(bean, McpSubscriptionRegistry.class, beans.createCreationalContext(bean));
            assertEquals(0, registry.size(), "the client proxy did not create the instance behind it");

            RecordingSample sample = new RecordingSample();
            panel.sample(sample);

            assertEquals("gauge", sample.kind("listens"));
            assertEquals(0.0, sample.number("listens"));
            assertEquals("absent", sample.kind("sessions"), "the session manager must still not exist");
        }
    }

    /**
     * The contract: {@code sample} reads the instance a bean already has and never asks for one. It fails the day
     * someone keeps a client proxy obtained at boot and calls it here — creating {@link McpSessionManager} starts
     * its {@code mcp-session-cleanup} scheduler, so the thread is checked too.
     */
    @Test
    void samplingCreatesNoBean() {
        try (VaubanContainer container = started()) {
            BeanManager beans = container.getBeanManager();
            Bean<?> sessionManager = beans.resolve(beans.getBeans(McpSessionManager.class));
            assertNotNull(sessionManager, "the container holds no McpSessionManager bean: the test proves nothing");
            assertNull(beans.getContext(sessionManager.getScope()).get(sessionManager),
                    "the session manager already existed before the sample");
            assertFalse(cleanupThreadAlive(), "the mcp-session-cleanup thread was already running");

            panel.sample(new RecordingSample());

            assertNull(beans.getContext(sessionManager.getScope()).get(sessionManager),
                    "sample() created the McpSessionManager bean: it must read Context.get(bean), never a proxy");
            assertFalse(cleanupThreadAlive(),
                    "sample() started the mcp-session-cleanup thread: a panel measures, it does not create");
            for (Class<?> type : SERVER_BEANS) {
                Bean<?> bean = beans.resolve(beans.getBeans(type));
                assertNull(beans.getContext(bean.getScope()).get(bean), "sample() created " + type.getSimpleName());
            }
        }
    }

    @Test
    void aSampleTakesUnderFiveMilliseconds() {
        try (VaubanContainer container = started()) {
            for (int warmup = 0; warmup < 100; warmup++) {
                panel.sample(new RecordingSample());
            }

            long start = System.nanoTime();
            panel.sample(new RecordingSample());
            long elapsedNanos = System.nanoTime() - start;

            assertTrue(elapsedNanos < 5_000_000L, "a sample took " + elapsedNanos / 1_000 + " us, over the 5 ms"
                    + " above which the console flags it slow");
        }
    }

    @Test
    void afterStopTheSampleWritesNothing() {
        try (VaubanContainer container = started()) {
            RecordingSample before = new RecordingSample();
            panel.sample(before);
            assertFalse(before.isEmpty(), "the panel wrote nothing while the container was up");

            panel.stop();

            RecordingSample after = new RecordingSample();
            panel.sample(after);
            assertTrue(after.isEmpty(), "the panel wrote " + after.keys() + " after stop");
        }
    }

    @Test
    void aTwinServerLeavesThePanelInert() {
        try (VaubanContainer container = started()) {
            BeanManager beans = container.getBeanManager();

            RecordingSample twin = new RecordingSample();
            McpLiveBeans.of(new McpInspection(true, 0, false), beans).sample(twin);

            RecordingSample notTwin = new RecordingSample();
            McpLiveBeans.of(new McpInspection(false, 0, false), beans).sample(notTwin);

            assertTrue(twin.isEmpty(), "a twin MCP server was sampled: the panel wrote " + twin.keys());
            assertEquals(ALL_KEYS, List.copyOf(notTwin.keys()),
                    "the same container, not a twin, must be sampled: otherwise the test proves nothing");
        }
    }

    @Test
    void beforeStartTheSampleWritesNothing() {
        RecordingSample sample = new RecordingSample();

        new McpLivePanel().sample(sample);

        assertTrue(sample.isEmpty());
    }

    /** A container with the MCP server's beans, with {@code panel} started on it. */
    private VaubanContainer started() {
        VaubanContainer container = McpTestContainers.container(SERVER_BEANS);
        panel.start(new FakeExtensionContext(container));
        return container;
    }

    private static boolean cleanupThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> "mcp-session-cleanup".equals(thread.getName()));
    }
}
