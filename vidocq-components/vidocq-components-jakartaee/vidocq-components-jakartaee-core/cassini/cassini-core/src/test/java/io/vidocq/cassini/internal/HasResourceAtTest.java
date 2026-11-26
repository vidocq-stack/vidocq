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
package io.vidocq.cassini.internal;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A transport that puts several handlers on one prefix asks the REST stack whether it has a resource
 * for a path before it hands the request over: a path no resource matches goes to the next handler
 * (vidocq#802). Any HTTP method counts, so that a path with a resource still answers 405 or OPTIONS.
 */
class HasResourceAtTest {

    @Path("/things")
    public static class Things {
        @GET @Path("/{id}") public String get() { return "{}"; }
        @POST public String create(String body) { return body; }
    }

    private final DefaultCassiniHttpAdapter adapter = new DefaultCassiniHttpAdapter(
            new UriRouter(List.copyOf(RouteRegistry.discover(Things.class))), new Invoker(c -> null));

    @Test
    void aPathAResourceMatchesWhateverTheMethod() {
        assertTrue(adapter.hasResourceAt("/things"));
        assertTrue(adapter.hasResourceAt("/things/42"));
        assertTrue(adapter.hasResourceAt("/things/42;v=1"));
    }

    @Test
    void aPathNoResourceMatches() {
        assertFalse(adapter.hasResourceAt("/"));
        assertFalse(adapter.hasResourceAt("/index.html"));
        assertFalse(adapter.hasResourceAt("/things/42/parts"));
    }
}
