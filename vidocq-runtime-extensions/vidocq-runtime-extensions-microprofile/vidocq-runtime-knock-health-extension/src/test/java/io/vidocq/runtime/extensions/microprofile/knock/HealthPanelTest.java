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
package io.vidocq.runtime.extensions.microprofile.knock;

import io.vidocq.knock.runtime.HealthCheckRegistries;
import io.vidocq.knock.runtime.KnockHealthService;
import io.vidocq.knock.spi.HealthCheckRegistry;
import io.vidocq.knock.spi.ProbeType;
import io.vidocq.runtime.spi.devconsole.Chart;
import io.vidocq.runtime.spi.devconsole.Series;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The live values of the {@code health} panel: the last answers Knock recorded, never a check called. */
class HealthPanelTest {

    private HealthCheckRegistry registry;
    private AtomicInteger calls;
    private AtomicBoolean dbUp;

    @BeforeEach
    void setUp() {
        registry = HealthCheckRegistries.newRegistry();
        calls = new AtomicInteger();
        dbUp = new AtomicBoolean(true);
        registry.register(ProbeType.LIVENESS, "com.acme.AppLivenessCheck_ClientProxy", counting(() ->
                HealthCheckResponse.up("app")));
        registry.register(ProbeType.READINESS, "com.acme.DatabaseCheck", counting(() ->
                HealthCheckResponse.named("database").status(dbUp.get()).withData("pool", 8).build()));
    }

    private HealthCheck counting(HealthCheck check) {
        return () -> {
            calls.incrementAndGet();
            return check.call();
        };
    }

    private RecordingSample sample() {
        RecordingSample out = new RecordingSample();
        HealthPanel.write(registry, ZoneOffset.UTC, out);
        return out;
    }

    @Test
    void beforeAnyProbeRequestEveryCheckIsNeverCalledAndSamplingCallsNone() {
        RecordingSample out = sample();

        assertEquals(List.of("liveness", "readiness"), List.copyOf(out.groups().keySet()),
                "a probe without a check has no group");
        RecordingSample liveness = out.groups().get("liveness");
        assertEquals("absent", liveness.kind("status"));
        assertEquals(HealthPanel.NEVER_CALLED, liveness.text("status"));
        assertEquals("absent", liveness.kind("app-liveness-check"));
        assertEquals(HealthPanel.NEVER_CALLED, liveness.text("app-liveness-check"));
        assertFalse(liveness.keys().contains("up"), "no count before an answer: it would read as a measure");
        assertEquals(0, calls.get(), "sampling called a check");
    }

    @Test
    void afterAHealthRequestEachCheckShowsItsLastStatusTimeAndData() {
        new KnockHealthService(registry).report(ProbeType.ALL);
        int afterRequest = calls.get();

        RecordingSample out = sample();

        RecordingSample readiness = out.groups().get("readiness");
        assertEquals("UP", readiness.text("status"));
        assertEquals(1, readiness.number("database-check"));
        assertEquals("text", readiness.kind("database-check.at"));
        assertEquals(1, readiness.number("up"));
        assertEquals(0, readiness.number("down"));
        assertEquals("table", readiness.kind("checks"));
        assertTrue(readiness.text("checks").contains("pool=8"), readiness.text("checks"));
        assertTrue(readiness.text("checks").contains("database"), readiness.text("checks"));
        assertEquals(1, out.groups().get("liveness").number("app-liveness-check"));
        assertEquals(afterRequest, calls.get(), "sampling called a check");
    }

    @Test
    void aCheckThatTurnsDownShowsDownAtTheNextRequestOnly() {
        KnockHealthService service = new KnockHealthService(registry);
        service.report(ProbeType.READINESS);
        dbUp.set(false);

        assertEquals(1, sample().groups().get("readiness").number("database-check"),
                "nothing changes until a probe request runs the check");

        service.report(ProbeType.ALL);
        RecordingSample readiness = sample().groups().get("readiness");

        assertEquals(0, readiness.number("database-check"));
        assertEquals("DOWN", readiness.text("status"));
        assertEquals(0, readiness.number("up"));
        assertEquals(1, readiness.number("down"));
    }

    @Test
    void aProbeRequestOnlyAnswersForItsOwnChecks() {
        new KnockHealthService(registry).report(ProbeType.LIVENESS);

        RecordingSample out = sample();

        assertEquals("UP", out.groups().get("liveness").text("status"));
        assertEquals("absent", out.groups().get("readiness").kind("status"));
    }

    @Test
    void theChartPlotsTheChecksUpAndDownPerProbe() {
        List<Chart> charts = HealthPanel.CHARTS;

        assertEquals(1, charts.size());
        assertEquals(List.of(Series.area("up"), Series.stacked("down")), charts.getFirst().series());
    }

    @Test
    void pastTheValuesAGroupHoldsTheRestOfTheChecksIsOmitted() {
        for (int i = 0; i < 40; i++) {
            registry.register(ProbeType.STARTUP, "com.acme.Check" + (char) ('a' + i % 26) + i,
                    () -> HealthCheckResponse.up("x"));
        }
        new KnockHealthService(registry).report(ProbeType.STARTUP);

        RecordingSample startup = sample().groups().get("startup");

        assertTrue(startup.keys().size() <= 64, "a scope keeps 64 values: " + startup.keys().size());
        assertEquals("11 checks omitted", startup.text("omitted"));
        assertEquals(40, startup.number("up"), "the counts cover every check");
    }
}
