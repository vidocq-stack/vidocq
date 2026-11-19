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
package io.vidocq.grimm.internal.model;

import io.vidocq.grimm.internal.serialization.JsonSerializer;
import io.vidocq.grimm.internal.serialization.YamlSerializer;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MP OpenAPI 4.2 (#698): for the base dialect, unknown schema properties are extensions. */
class SchemaImplExtensionsTest {

    private static final String EXT = "my-extension";

    @Test
    void extensionSetForUnknownProperty() {
        Schema s = OASFactory.createSchema().set(EXT, "v");
        assertEquals("v", s.getExtensions().get(EXT));
        assertTrue(s.hasExtension(EXT));
        assertEquals("v", s.getExtension(EXT));
    }

    @Test
    void extensionSetAllForUnknownProperty() {
        Schema s = OASFactory.createSchema();
        s.setAll(Map.of(EXT, "v", "type", List.of(Schema.SchemaType.STRING)));
        assertEquals(Map.of(EXT, "v"), s.getExtensions());
        assertEquals(List.of(Schema.SchemaType.STRING), s.getType());
    }

    @Test
    void extensionAvailableFromGet() {
        Schema s = OASFactory.createSchema().addExtension(EXT, "v");
        assertEquals("v", s.get(EXT));
        assertTrue(s.getAll().containsKey(EXT));
    }

    @Test
    void extensionSetWithNonnullDialect() {
        Schema s = OASFactory.createSchema()
                .schemaDialect("https://spec.openapis.org/oas/3.1/dialect/base")
                .addExtension(EXT, "v");
        assertEquals("v", s.get(EXT));
        assertEquals("v", s.getExtension(EXT));
    }

    @Test
    void setAllClearsExtensions() {
        Schema s = OASFactory.createSchema().addExtension(EXT, "v");
        s.setAll(Map.of("type", List.of(Schema.SchemaType.INTEGER)));
        assertTrue(s.getExtensions().isEmpty());
        assertNull(s.get(EXT));
    }

    @Test
    void nullExtensionNotAdded() {
        Schema s = OASFactory.createSchema().addExtension(EXT, null);
        assertFalse(s.hasExtension(EXT));
        assertTrue(s.getExtensions().isEmpty());
    }

    @Test
    void setNullRemovesProperty() {
        Schema s = OASFactory.createSchema().addExtension(EXT, "v");
        s.set(EXT, null);
        assertFalse(s.hasExtension(EXT));
    }

    @Test
    void removeExtensionRemovesFromGet() {
        Schema s = OASFactory.createSchema().addExtension(EXT, "v");
        s.removeExtension(EXT);
        assertNull(s.get(EXT));
    }

    @Test
    void extensionSerializedExactlyOnce() {
        Schema s = OASFactory.createSchema().type(List.of(Schema.SchemaType.STRING)).addExtension("x-once", "v");

        OpenAPI doc = OASFactory.createOpenAPI()
                .components(OASFactory.createComponents().addSchema("S", s));

        String json = new JsonSerializer().serialize(doc);
        String yaml = new YamlSerializer().serialize(doc);

        assertEquals(1, count(json, "\"x-once\""), json);
        assertEquals(1, count(yaml, "x-once:"), yaml);
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
