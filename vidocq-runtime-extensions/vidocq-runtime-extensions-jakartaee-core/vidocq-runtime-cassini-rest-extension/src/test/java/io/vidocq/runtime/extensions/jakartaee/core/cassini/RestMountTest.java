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
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RestMountTest {

    @Path("/tasks")
    static class TaskResource {}

    @Path("/users")
    static class UserResource {}

    /** One provider, two kinds: it is listed under both. */
    @Provider
    static class AuditFilter implements ContainerRequestFilter, ExceptionMapper<IllegalStateException> {
        @Override
        public void filter(ContainerRequestContext context) {}

        @Override
        public Response toResponse(IllegalStateException exception) {
            return null;
        }
    }

    @Provider
    static class NotFoundMapper implements ExceptionMapper<IllegalArgumentException> {
        @Override
        public Response toResponse(IllegalArgumentException exception) {
            return null;
        }
    }

    private static final RouteDescription LIST =
            new RouteDescription("GET", "/tasks", TaskResource.class.getName(), "list", Set.of(), Set.of());

    private static Set<Class<?>> classes(Class<?>... classes) {
        return new LinkedHashSet<>(List.of(classes));
    }

    @Test
    void keeps_resource_and_provider_classes_by_name_and_kind() {
        RestMount mount = RestMount.of("vidocq.rest", "default", "/api", true,
                classes(UserResource.class, NotFoundMapper.class, TaskResource.class, AuditFilter.class),
                List.of(LIST), null);

        assertEquals(List.of(TaskResource.class.getName(), UserResource.class.getName()), mount.resources());
        assertEquals(List.of(AuditFilter.class.getName(), NotFoundMapper.class.getName()), mount.providers());
        assertEquals(List.of(
                        new RestMount.ProviderGroup("request filters", List.of(AuditFilter.class.getName())),
                        new RestMount.ProviderGroup("exception mappers",
                                List.of(AuditFilter.class.getName(), NotFoundMapper.class.getName()))),
                mount.providerGroups());
    }

    @Test
    void a_route_is_on_the_listener_under_the_mount_prefix() {
        RestMount mount = RestMount.of("vidocq.rest", "default", "/api", true, classes(), List.of(LIST), null);

        assertEquals("/api/tasks", mount.pathOnListener(LIST));
    }

    @Test
    void a_root_mount_adds_no_prefix() {
        RestMount mount = RestMount.of("vidocq.rest", "default", "", true, classes(), List.of(LIST), null);

        assertEquals("/tasks", mount.pathOnListener(LIST));
        assertEquals("/", mount.displayPrefix());
    }

    @Test
    void a_mount_that_keeps_the_prefix_hands_the_full_path_to_cassini() {
        RouteDescription health = new RouteDescription("GET", "/health", "com.acme.Health", "check", Set.of(), Set.of());
        RestMount mount = RestMount.of("health", "default", "/health", false, classes(), List.of(health), null);

        assertEquals("/health", mount.pathOnListener(health));
    }

    @Test
    void holds_no_class_and_cannot_be_changed() {
        RestMount mount = RestMount.of("vidocq.rest", "default", "/api", true,
                classes(TaskResource.class, AuditFilter.class), List.of(LIST), null);

        assertThrows(UnsupportedOperationException.class, () -> mount.routes().clear());
        assertThrows(UnsupportedOperationException.class, () -> mount.resources().clear());
        assertThrows(UnsupportedOperationException.class, () -> mount.providerGroups().getFirst().classes().clear());
    }
}
