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
package io.vidocq.grimm.internal;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.Paths;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link ApplicationPathPrefix}: the {@code @ApplicationPath} in front of the annotated paths (BUG-20261123-49). */
class ApplicationPathPrefixTest {

    @ApplicationPath("/zeta")
    public static final class ZetaApplication extends Application {
    }

    @ApplicationPath("/alpha/")
    public static final class AlphaApplication extends Application {
    }

    /** Not an {@code Application}: its annotation names no application path. */
    @ApplicationPath("/ignored")
    public static final class NotAnApplication {
    }

    @Test
    void theValueIsNormalized() {
        assertEquals("/api", ApplicationPathPrefix.normalize("api/"));
        assertEquals("/api", ApplicationPathPrefix.normalize(" /api// "));
        assertEquals("/api/v1", ApplicationPathPrefix.normalize("/api/v1"));
        assertEquals("", ApplicationPathPrefix.normalize("/"));
        assertEquals("", ApplicationPathPrefix.normalize(""));
        assertEquals("", ApplicationPathPrefix.normalize(null));
    }

    @Test
    void theFirstApplicationByClassNameWins() {
        assertEquals("/alpha", ApplicationPathPrefix.of(List.of(ZetaApplication.class, AlphaApplication.class)));
        assertEquals("", ApplicationPathPrefix.of(List.of(NotAnApplication.class, String.class)));
    }

    @Test
    void everyPathTakesThePrefixInItsOrder() {
        Paths paths = OASFactory.createObject(Paths.class)
                .addPathItem("/", OASFactory.createObject(PathItem.class))
                .addPathItem("items", OASFactory.createObject(PathItem.class))
                .addPathItem("/items/{id}", OASFactory.createObject(PathItem.class));
        OpenAPI model = OASFactory.createObject(OpenAPI.class).paths(paths);

        ApplicationPathPrefix.applyTo(model, "/api");

        assertEquals(List.of("/api", "/api/items", "/api/items/{id}"),
                List.copyOf(model.getPaths().getPathItems().keySet()));
    }

    @Test
    void noPrefixOrNoPathsChangesNothing() {
        OpenAPI empty = OASFactory.createObject(OpenAPI.class);
        ApplicationPathPrefix.applyTo(empty, "/api");
        assertNull(empty.getPaths());

        Paths paths = OASFactory.createObject(Paths.class).addPathItem("/items", OASFactory.createObject(PathItem.class));
        OpenAPI model = OASFactory.createObject(OpenAPI.class).paths(paths);
        ApplicationPathPrefix.applyTo(model, "");
        assertEquals(List.of("/items"), List.copyOf(model.getPaths().getPathItems().keySet()));
    }
}
