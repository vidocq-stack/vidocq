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

import io.vidocq.grimm.internal.config.GrimmConfig;
import io.vidocq.grimm.internal.reader.StaticFileReader;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Spec §4.4: the final document is the merge of the static file and the
 * annotation model — a path present only in META-INF/openapi.yaml (the TCK's
 * Airlines /streams) must survive when annotated resources contribute paths
 * of their own.
 */
class StaticPlusAnnotationsMergeTest {

    @Path("/annotated")
    static class AnnotatedResource {
        @GET
        public String get() {
            return "ok";
        }
    }

    private static final String STATIC_DOC = """
            openapi: 3.1.0
            info:
              title: static
              version: "1.0"
            paths:
              /streams:
                post:
                  description: subscribes a client to receive out-of-band data
                  responses:
                    "201":
                      description: subscription successfully created
            """;

    @AfterEach
    void cleanup() {
        StaticFileReader.clearExplicitDocument();
    }

    @Test
    void staticOnlyPathSurvivesTheMergeWithAnnotations() {
        StaticFileReader.setExplicitDocument("META-INF/openapi.yaml", STATIC_DOC);

        OpenAPI model = new ModelBuilder().build(GrimmConfig.defaults(),
                List.of(AnnotatedResource.class));

        assertNotNull(model.getPaths().getPathItem("/annotated"),
                "annotated resources must contribute their paths");
        assertNotNull(model.getPaths().getPathItem("/streams"),
                "paths defined only in the static document must survive the merge");
    }
}
