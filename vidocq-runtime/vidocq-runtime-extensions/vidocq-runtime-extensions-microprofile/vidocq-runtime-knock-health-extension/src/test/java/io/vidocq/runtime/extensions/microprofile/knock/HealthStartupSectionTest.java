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
import io.vidocq.knock.spi.HealthCheckRegistry;
import io.vidocq.knock.spi.ProbeType;
import io.vidocq.runtime.extensions.microprofile.knock.live.KnockLiveBean;
import io.vidocq.runtime.spi.report.Verbosity;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code health} section of the startup report: the probes and their check names, no check called. */
class HealthStartupSectionTest {

    private static final String RESOURCE = HealthStartupSection.HEALTH_RESOURCE;

    private final AtomicInteger calls = new AtomicInteger();

    private HealthCheck check() {
        return () -> {
            calls.incrementAndGet();
            return HealthCheckResponse.up("x");
        };
    }

    private HealthCheckRegistry registry() {
        HealthCheckRegistry registry = HealthCheckRegistries.newRegistry();
        registry.register(ProbeType.LIVENESS, "com.acme.AppLivenessCheck_ClientProxy", check());
        registry.register(ProbeType.READINESS, "com.acme.DatabaseCheck", check());
        registry.register(ProbeType.READINESS, "com.acme.QueueCheck", check());
        return registry;
    }

    @Test
    void theSummaryCountsTheChecksPerProbeAndTheListsNameThem() {
        RecordingSection section = new RecordingSection();

        HealthStartupSection.write(registry(), null, new FakeReportContext(Verbosity.DETAILED), section);

        assertEquals("3 checks: 1 liveness, 2 readiness, 0 startup", section.summary);
        assertEquals(List.of("AppLivenessCheck"), section.lists.get("liveness"));
        assertEquals(List.of("DatabaseCheck", "QueueCheck"), section.lists.get("readiness"));
        assertTrue(!section.lists.containsKey("startup"), "a probe without a check lists nothing");
        assertEquals(0, calls.get(), "the report called a check");
    }

    @Test
    void theLinkPointsAtTheHealthRouteTheRestSectionDeclared() {
        RecordingSection section = new RecordingSection();
        FakeReportContext context = new FakeReportContext(Verbosity.SUMMARY)
                .route(RESOURCE, "http://localhost:18093/api/health/live")
                .route(RESOURCE, "http://localhost:18093/api/health");

        HealthStartupSection.write(registry(), null, context, section);

        assertEquals(List.of(new RecordingSection.Link("Health", "default", "/api/health")), section.links);
        assertTrue(section.lists.isEmpty(), "lists are for the detailed report only");
    }

    @Test
    void withoutADeclaredRouteTheLinkFallsBackToTheResourcePath() {
        RecordingSection section = new RecordingSection();

        HealthStartupSection.write(registry(), null, new FakeReportContext(Verbosity.SUMMARY), section);

        assertEquals(List.of(new RecordingSection.Link("Health", "default", "/health")), section.links);
    }

    @Test
    void aRegistryNotCreatedYetMeansNoCheckWasRegistered() {
        RecordingSection section = new RecordingSection();

        HealthStartupSection.write(null, KnockLiveBean.NOT_CREATED_YET, new FakeReportContext(Verbosity.DETAILED),
                section);

        assertEquals("no health check (registry not created yet)", section.summary);
    }
}
