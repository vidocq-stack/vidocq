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
package io.vidocq.grimm.internal.config;

import io.vidocq.grimm.internal.schema.SchemaRegistry;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.servers.Server;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ConfigApplier} — spec §4.1 keys {@code mp.openapi.servers}
 * and {@code mp.openapi.schema.<FQCN>} application onto the assembled model.
 */
class ConfigApplierTest {

    @Test
    void applyServers_replacesModelServersFromCsv() {
        // Spec §4.1: mp.openapi.servers replaces top-level servers.
        OpenAPI model = OASFactory.createObject(OpenAPI.class);
        model.setServers(List.of(OASFactory.createObject(Server.class).url("https://old")));

        GrimmConfig cfg = GrimmConfig.fromMap(Map.of(
                "mp.openapi.servers", "https://a, https://b"
        ));
        ConfigApplier.applyServers(model, cfg);

        assertEquals(2, model.getServers().size());
        assertEquals("https://a", model.getServers().get(0).getUrl());
        assertEquals("https://b", model.getServers().get(1).getUrl());
    }

    @Test
    void applyServers_leavesModelUntouchedWhenUnset() {
        // No mp.openapi.servers configured → existing servers preserved.
        OpenAPI model = OASFactory.createObject(OpenAPI.class);
        model.setServers(List.of(OASFactory.createObject(Server.class).url("https://kept")));
        ConfigApplier.applyServers(model, GrimmConfig.defaults());
        assertEquals(1, model.getServers().size());
        assertEquals("https://kept", model.getServers().get(0).getUrl());
    }

    @Test
    void applySchemaOverrides_registersJsonSchemaUnderClassSimpleName() {
        // Spec §4.1: mp.openapi.schema.<FQCN> parses a JSON object into a Schema.
        SchemaRegistry registry = new SchemaRegistry();
        String json = "{\"type\":\"string\",\"format\":\"date\",\"description\":\"birthday\"}";
        GrimmConfig cfg = GrimmConfig.fromMap(Map.of(
                "mp.openapi.schema." + Sample.class.getName(), json
        ));

        var assigned = ConfigApplier.applySchemaOverrides(registry, cfg);
        assertEquals(Sample.class.getName(), assigned.keySet().iterator().next());
        assertEquals("Sample", assigned.get(Sample.class.getName()));

        Schema registered = registry.snapshot().get("Sample");
        assertNotNull(registered);
        assertEquals(Schema.SchemaType.STRING, registered.getType().get(0));
        assertEquals("date", registered.getFormat());
        assertEquals("birthday", registered.getDescription());
    }

    @Test
    void applySchemaOverrides_skipsUnknownFqcn() {
        // Unknown class → silently skipped.
        SchemaRegistry registry = new SchemaRegistry();
        GrimmConfig cfg = GrimmConfig.fromMap(Map.of(
                "mp.openapi.schema.com.unknown.NotARealClass", "{\"type\":\"string\"}"
        ));
        var assigned = ConfigApplier.applySchemaOverrides(registry, cfg);
        assertTrue(assigned.isEmpty());
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test
    void applySchemaOverrides_parsesComplexSchemaWithPropertiesAndArray() {
        // Verify nested properties + array items survive the JSON → Schema parse.
        SchemaRegistry registry = new SchemaRegistry();
        String json = "{"
                + "\"type\":\"object\","
                + "\"required\":[\"name\"],"
                + "\"properties\":{"
                + "  \"name\":{\"type\":\"string\"},"
                + "  \"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}"
                + "}}";
        GrimmConfig cfg = GrimmConfig.fromMap(Map.of(
                "mp.openapi.schema." + Sample.class.getName(), json));

        ConfigApplier.applySchemaOverrides(registry, cfg);
        Schema s = registry.snapshot().get("Sample");
        assertEquals(Schema.SchemaType.OBJECT, s.getType().get(0));
        assertEquals(List.of("name"), s.getRequired());
        assertEquals(Schema.SchemaType.STRING, s.getProperties().get("name").getType().get(0));
        Schema tags = s.getProperties().get("tags");
        assertEquals(Schema.SchemaType.ARRAY, tags.getType().get(0));
        assertEquals(Schema.SchemaType.STRING, tags.getItems().getType().get(0));
    }

    @Test
    void applySchemaOverrides_usesTheTypedStaticMapper() {
        // Same mapping as a static file: standard keywords are typed, unknown keywords are kept,
        // $ref is verbatim, and the Vidocq-only "name" key is not part of the schema.
        SchemaRegistry registry = new SchemaRegistry();
        String json = "{\"name\":\"Renamed\",\"type\":\"object\",\"exclusiveMinimum\":0,"
                + "\"oneOf\":[{\"$ref\":\"Pet.yaml\"},{\"type\":\"null\"}],"
                + "\"discriminator\":{\"propertyName\":\"kind\"},"
                + "\"custom-keyword\":{\"a\":1},\"x-vendor\":\"v\","
                + "\"properties\":{\"p\":{\"minItems\":2,\"const\":\"c\"}}}";
        GrimmConfig cfg = GrimmConfig.fromMap(Map.of(
                "mp.openapi.schema." + Sample.class.getName(), json));

        var assigned = ConfigApplier.applySchemaOverrides(registry, cfg);
        assertEquals("Renamed", assigned.get(Sample.class.getName()));
        Schema s = registry.snapshot().get("Renamed");
        assertEquals(new BigDecimal("0"), s.getExclusiveMinimum());
        assertEquals("Pet.yaml", s.getOneOf().get(0).getRef());
        assertEquals("kind", s.getDiscriminator().getPropertyName());
        assertEquals(Map.of("a", 1L), s.get("custom-keyword"));
        assertEquals("v", s.getExtensions().get("x-vendor"));
        assertNull(s.get("name"));
        assertEquals(2, s.getProperties().get("p").getMinItems());
        assertEquals("c", s.getProperties().get("p").getConstValue());
    }

    @Test
    void applySchemaOverrides_refAliasExpandsButExplicitRefStaysVerbatim() {
        SchemaRegistry registry = new SchemaRegistry();
        String json = "{\"properties\":{\"a\":{\"ref\":\"Pet\"},\"b\":{\"$ref\":\"Pet.yaml\"}}}";
        ConfigApplier.applySchemaOverrides(registry, GrimmConfig.fromMap(Map.of(
                "mp.openapi.schema." + Sample.class.getName(), json)));
        Schema s = registry.snapshot().get("Sample");
        assertEquals("Pet.yaml", s.getProperties().get("b").getRef());

        String top = "{\"ref\":\"Pet\"}";
        SchemaRegistry other = new SchemaRegistry();
        ConfigApplier.applySchemaOverrides(other, GrimmConfig.fromMap(Map.of(
                "mp.openapi.schema." + Sample.class.getName(), top)));
        assertEquals("#/components/schemas/Pet", other.snapshot().get("Sample").getRef());

        String explicit = "{\"$ref\":\"Pet\"}";
        SchemaRegistry third = new SchemaRegistry();
        ConfigApplier.applySchemaOverrides(third, GrimmConfig.fromMap(Map.of(
                "mp.openapi.schema." + Sample.class.getName(), explicit)));
        assertEquals("Pet", third.snapshot().get("Sample").getRef());
    }

    @Test
    void applySchemaOverrides_refAliasIsAcceptedAtEveryLevel() {
        SchemaRegistry registry = new SchemaRegistry();
        String json = "{\"properties\":{\"a\":{\"ref\":\"Pet\"},\"b\":{\"$ref\":\"Pet.yaml\",\"ref\":\"Ignored\"},"
                + "\"ref\":{\"type\":\"string\"}},"
                + "\"items\":{\"ref\":\"Item\"},"
                + "\"allOf\":[{\"ref\":\"Base\"}],"
                + "\"additionalProperties\":{\"ref\":\"Extra\"}}";
        ConfigApplier.applySchemaOverrides(registry, GrimmConfig.fromMap(Map.of(
                "mp.openapi.schema." + Sample.class.getName(), json)));
        Schema s = registry.snapshot().get("Sample");
        assertEquals("#/components/schemas/Pet", s.getProperties().get("a").getRef());
        assertEquals("Pet.yaml", s.getProperties().get("b").getRef());
        assertEquals(Schema.SchemaType.STRING, s.getProperties().get("ref").getType().get(0));
        assertEquals("#/components/schemas/Item", s.getItems().getRef());
        assertEquals("#/components/schemas/Base", s.getAllOf().get(0).getRef());
        assertEquals("#/components/schemas/Extra", s.getAdditionalPropertiesSchema().getRef());
    }

    @SuppressWarnings("unused")
    static final class Sample {
        String name;
    }
}

