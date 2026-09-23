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
package io.vidocq.runtime.extensions.jakartaee.core.cassini;

import io.vidocq.cassini.spi.http.RouteDescription;
import io.vidocq.runtime.spi.report.Verbosity;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestStartupSectionTest {

    @Path("/tasks")
    static class TaskResource {}

    @Path("/health")
    static class HealthResource {}

    @Provider
    static class AuditFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext context) {}
    }

    private static final String TASKS = TaskResource.class.getName();

    private static final List<RouteDescription> TASK_ROUTES = List.of(
            new RouteDescription("GET", "/tasks/{id}", TASKS, "one", Set.of("application/json"), Set.of()),
            new RouteDescription("GET", "/tasks", TASKS, "list", Set.of("application/json"), Set.of()),
            new RouteDescription("", "/tasks/any", TASKS, "any", Set.of(), Set.of()));

    private static RestMount api() {
        return RestMount.of("vidocq.rest", "default", "/api", true,
                new LinkedHashSet<>(List.of(TaskResource.class, AuditFilter.class)), TASK_ROUTES, null);
    }

    private static RestMount health() {
        return RestMount.of("health", "default", "/health", false, Set.of(HealthResource.class),
                List.of(new RouteDescription("GET", "/health", HealthResource.class.getName(), "check",
                        Set.of(), Set.of())), null);
    }

    private static RecordingSection write(Verbosity verbosity, List<RestMount> mounts) {
        RecordingSection section = new RecordingSection();
        RestStartupSection.write(mounts, listener -> List.of(), new FakeReportContext(verbosity), section);
        return section;
    }

    @Test
    void says_so_when_nothing_is_mounted() {
        RecordingSection section = write(Verbosity.DETAILED, List.of());

        assertEquals("no resource class", section.summary);
        assertTrue(section.routes.isEmpty());
        assertTrue(section.anomalies.isEmpty());
    }

    @Test
    void sums_up_the_classes_the_routes_and_where_they_are_mounted() {
        RecordingSection section = write(Verbosity.SUMMARY, List.of(api()));

        assertEquals("1 resource class, 3 routes, 1 provider at /api", section.summary);
    }

    @Test
    void declares_every_route_in_match_order_on_its_listener() {
        RecordingSection section = write(Verbosity.SUMMARY, List.of(api()));

        assertEquals(List.of(
                        new RecordingSection.Route("default", "GET", "/api/tasks/{id}", TASKS + "#one"),
                        new RecordingSection.Route("default", "GET", "/api/tasks", TASKS + "#list"),
                        new RecordingSection.Route("default", "*", "/api/tasks/any", TASKS + "#any")),
                section.routes);
    }

    @Test
    void writes_no_row_below_detailed() {
        RecordingSection section = write(Verbosity.SUMMARY, List.of(api()));

        assertTrue(section.rows.isEmpty());
        assertTrue(section.lists.isEmpty());
    }

    @Test
    void details_each_mount_and_the_providers_by_kind() {
        RecordingSection section = write(Verbosity.DETAILED, List.of(api(), health()));

        assertEquals("2 resource classes, 4 routes, 1 provider at /api, /health", section.summary);
        assertEquals("listener default, prefix /api, 3 routes", section.rows.get("mount vidocq.rest"));
        assertEquals("listener default, prefix /health (not stripped), 1 route", section.rows.get("mount health"));
        assertEquals(List.of(HealthResource.class.getName(), TASKS), section.lists.get("resources"));
        assertEquals(List.of(AuditFilter.class.getName()), section.lists.get("request filters"));
    }

    @Test
    void offersToOpenThePagesAdvertisedOnItsListenersAtEveryLevel() {
        RecordingSection section = new RecordingSection();
        RestStartupSection.write(List.of(api(), health()),
                listener -> "default".equals(listener)
                        ? List.of(new io.vidocq.runtime.extensions.essentials.chappe.ChappeMountPoint.Page(
                                "Swagger UI", "/openapi/ui/"))
                        : List.of(),
                new FakeReportContext(Verbosity.SUMMARY), section);

        assertEquals(List.of(new RecordingSection.Link("Swagger UI", "default", "/openapi/ui/")), section.links,
                "once per listener, though two mounts are on it");
    }
}
