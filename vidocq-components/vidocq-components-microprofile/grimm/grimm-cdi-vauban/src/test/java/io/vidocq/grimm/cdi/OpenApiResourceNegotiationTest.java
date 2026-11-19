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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MP OpenAPI 4.2 §2.2 / §2.3: the {@code /openapi} endpoint serves YAML by default, JSON when
 * asked by the {@code Accept} header, and the {@code format} query parameter overrides both.
 */
class OpenApiResourceNegotiationTest {

    private static final String JSON = OpenApiResource.MEDIA_TYPE_JSON;
    private static final String YAML = OpenApiResource.MEDIA_TYPE_YAML;

    @Path("/books")
    public static class BooksResource {
        @GET
        public String list() {
            return "[]";
        }
    }

    @Test
    void cdiConstructorServesTheCachedDocument() {
        GrimmModelCache cache = new GrimmModelCache();
        cache.initialize(GrimmConfig.defaults(), List.of(BooksResource.class));

        var rendered = new OpenApiResource(cache).render("json", null);

        assertEquals(JSON, rendered.mediaType());
        assertTrue(rendered.body().contains("/books"), rendered.body());
    }

    @Test
    void ymlAndUpperCaseFormatsAreRecognised() {
        OpenApiResource resource = new OpenApiResource();

        assertEquals(YAML, resource.render("yml", JSON).mediaType());
        assertEquals(YAML, resource.render("YAML", JSON).mediaType());
        assertEquals(JSON, resource.render("JSON", YAML).mediaType());
    }

    @Test
    void unknownFormatFallsBackToTheAcceptHeader() {
        OpenApiResource resource = new OpenApiResource();

        assertEquals(JSON, resource.render("xml", "Application/JSON; charset=UTF-8").mediaType());
        assertEquals(YAML, resource.render("xml", "text/plain").mediaType());
    }

    @Test
    void defaultResourceServesAnEmptyOpenApi31Document() {
        var rendered = new OpenApiResource().render(null, null);

        assertEquals(YAML, rendered.mediaType());
        assertTrue(rendered.body().contains("3.1.0"), rendered.body());
    }

    @Test
    void nullDependenciesAreRejected() {
        assertThrows(NullPointerException.class, () -> new OpenApiResource((GrimmModelCache) null));
        assertThrows(NullPointerException.class, () -> OpenApiResource.fromSupplier(null));
    }

    @Test
    void autoDiscoveryInitialisesTheFactoryResolver() {
        assertDoesNotThrow(() -> new GrimmAutoDiscovery().initialize());
    }
}
