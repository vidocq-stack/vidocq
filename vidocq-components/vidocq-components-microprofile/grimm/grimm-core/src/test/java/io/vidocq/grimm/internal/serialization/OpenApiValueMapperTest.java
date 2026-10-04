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

import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * BUG-20261004-01: {@code Schema.getAll()} lists the standard properties as well as the
     * extensions, so the serializer must not write it next to the typed getters — each key once,
     * under its JSON name, with the value of the typed getter ({@code type} unwrapped).
     */
    @Test
    void toSerializable_writesEachSchemaPropertyOnceUnderItsJsonName() {
        Schema schema = OASFactory.createSchema()
                .type(List.of(Schema.SchemaType.STRING))
                .title("t")
                .defaultValue("d")
                .enumeration(List.of("a"))
                .schemaDialect("https://example.com/dialect")
                .comment("c")
                .constValue("k")
                .ifSchema(OASFactory.createSchema().minLength(1))
                .addExtension("x-a", "v")
                .set("unknownKeyword", 1);
        OpenAPI openAPI = OASFactory.createOpenAPI()
                .components(OASFactory.createComponents().addSchema("S", schema));

        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) OpenApiValueMapper.toSerializable(openAPI);
        Map<?, ?> s = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) root.get("components")).get("schemas")).get("S");

        assertEquals(Set.of("type", "title", "default", "enum", "$schema", "$comment", "const", "if",
                "x-a", "unknownKeyword"), s.keySet());
        assertEquals("string", s.get("type"));
        assertEquals(List.of("a"), s.get("enum"));

        String json = new JsonSerializer().serialize(openAPI);
        for (Object key : s.keySet()) {
            assertEquals(1, count(json, "\"" + key + "\":"), key + " in " + json);
        }
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}

