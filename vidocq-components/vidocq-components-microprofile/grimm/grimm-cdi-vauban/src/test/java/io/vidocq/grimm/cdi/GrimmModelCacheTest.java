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
package io.vidocq.grimm.cdi;

import io.vidocq.grimm.internal.config.GrimmConfig;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrimmModelCacheTest {

    @Test
    void getDocument_returnsDefaultSkeletonWhenUninitialized() {
        GrimmModelCache cache = new GrimmModelCache();
        OpenAPI doc = cache.getDocument();
        assertNotNull(doc);
        assertEquals("3.1.0", doc.getOpenapi());
    }

    @Test
    void initialize_buildsDocumentFromAnnotatedTypes() {
        GrimmModelCache cache = new GrimmModelCache();
        cache.initialize(GrimmConfig.defaults(), List.of(PetsResource.class));

        OpenAPI doc = cache.getDocument();
        assertNotNull(doc.getPaths());
        assertTrue(doc.getPaths().hasPathItem("/pets"));
        assertNotNull(doc.getPaths().getPathItem("/pets").getGET());
    }

    @Path("/pets")
    @Produces("application/json")
    static class PetsResource {
        @GET
        public String list() {
            return "[]";
        }
    }
}

