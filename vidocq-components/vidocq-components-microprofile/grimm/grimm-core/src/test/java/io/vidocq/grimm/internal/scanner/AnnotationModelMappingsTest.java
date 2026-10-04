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
package io.vidocq.grimm.internal.scanner;

import io.vidocq.grimm.internal.config.ScanConfig;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.ExternalDocumentation;
import org.eclipse.microprofile.openapi.annotations.extensions.Extension;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for the annotation-to-model mappings shared by both scanners (MP OpenAPI 4.2 §3).
 */
class AnnotationModelMappingsTest {

    @ExternalDocumentation(url = "https://example.org/docs", description = "More",
            extensions = @Extension(name = "x-owner", value = "team-a"))
    static class Full { }

    @ExternalDocumentation(url = "https://example.org/docs")
    static class NoDescription { }

    @Test
    void mapsUrlAndDescription() {
        var docs = AnnotationModelMappings.toModelExternalDocs(Full.class.getAnnotation(ExternalDocumentation.class));
        assertEquals("https://example.org/docs", docs.getUrl());
        assertEquals("More", docs.getDescription());
    }

    @Test
    void leavesDescriptionUnsetWhenEmpty() {
        var docs = AnnotationModelMappings.toModelExternalDocs(
                NoDescription.class.getAnnotation(ExternalDocumentation.class));
        assertEquals("https://example.org/docs", docs.getUrl());
        assertNull(docs.getDescription());
    }

    @Test
    void mapsExtensions() {
        var docs = AnnotationModelMappings.toModelExternalDocs(Full.class.getAnnotation(ExternalDocumentation.class));
        assertEquals("team-a", docs.getExtensions().get("x-owner"));
    }

    @Path("/e")
    static class EmptyUrlResource {
        @GET
        @ExternalDocumentation(description = "no url")
        public String get() {
            return "";
        }
    }

    @Test
    void emptyUrlOnAMethodProducesNoExternalDocs() {
        // The empty-url guard stays at each call site (class level and operation level alike).
        OpenAPI openAPI = new AnnotationScanner(ScanConfig.defaultConfig()).scanClasses(List.of(EmptyUrlResource.class));
        assertNotNull(openAPI.getPaths().getPathItem("/e").getGET());
        assertNull(openAPI.getPaths().getPathItem("/e").getGET().getExternalDocs());
    }
}
