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

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.info.Info;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiResourceTest {

    @Test
    void getOpenApi_usesFormatQueryParameterOverAcceptHeader() {
        // Spec §2.3: format query parameter overrides Accept negotiation.
        OpenApiResource resource = OpenApiResource.fromSupplier(sampleModelSupplier());

        var response = resource.render("json", "application/yaml");

        assertEquals("application/json", response.mediaType());
        assertNotNull(response.body());
        assertTrue(response.body().contains("\"openapi\""));
    }

    @Test
    void getOpenApi_defaultsToYamlWhenNoFormatAndNoAccept() {
        // Spec §2.2: YAML is the default representation.
        OpenApiResource resource = OpenApiResource.fromSupplier(sampleModelSupplier());

        var response = resource.render(null, null);

        assertEquals("application/yaml", response.mediaType());
        assertNotNull(response.body());
        assertTrue(response.body().contains("openapi:"));
    }

    @Test
    void getOpenApi_usesAcceptHeaderWhenFormatParameterIsMissing() {
        // Spec §2.2: Accept header drives representation when format is absent.
        OpenApiResource resource = OpenApiResource.fromSupplier(sampleModelSupplier());

        var response = resource.render(null, "application/json");

        assertEquals("application/json", response.mediaType());
    }

    @Test
    void getOpenApi_returnsJaxRsResponseWithExpectedMediaType() {
        // Keep this test runtime-provider free: validate endpoint metadata by reflection.
        assertNotNull(OpenApiResource.class.getAnnotation(jakarta.ws.rs.Path.class));
        try {
            var method = OpenApiResource.class.getMethod("getOpenApi", String.class, String.class);
            assertNotNull(method.getAnnotation(jakarta.ws.rs.GET.class));
            var produces = method.getAnnotation(jakarta.ws.rs.Produces.class);
            assertNotNull(produces);
            assertTrue(java.util.Arrays.asList(produces.value()).contains("application/json"));
            assertTrue(java.util.Arrays.asList(produces.value()).contains("application/yaml"));
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private Supplier<OpenAPI> sampleModelSupplier() {
        return () -> {
            Info info = OASFactory.createObject(Info.class)
                .title("M2 API")
                .version("1.0.0");
            return OASFactory.createObject(OpenAPI.class)
                .openapi("3.1.0")
                .info(info);
        };
    }
}


