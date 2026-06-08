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
package io.vidocq.grimm.internal.serialization;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OpenApiValueMapperTest {

    @Test
    void toSerializable_mapsAdditionalPropertiesBooleanToOpenApiKey() {
        OpenAPI openAPI = OASFactory.createObject(OpenAPI.class);
        var components = OASFactory.createObject(org.eclipse.microprofile.openapi.models.Components.class);
        Schema schema = OASFactory.createObject(Schema.class);
        schema.addType(Schema.SchemaType.OBJECT);
        schema.setAdditionalPropertiesBoolean(Boolean.TRUE);
        components.addSchema("Payload", schema);
        openAPI.setComponents(components);

        Object value = OpenApiValueMapper.toSerializable(openAPI);
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) value;
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) ((Map<?, ?>) ((Map<?, ?>) root.get("components")).get("schemas")).get("Payload");

        assertNotNull(payload);
        assertEquals(Boolean.TRUE, payload.get("additionalProperties"));
    }
}

