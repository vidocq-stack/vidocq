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
package io.vidocq.grimm.internal.schema;

import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.models.media.Schema.SchemaType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A JAX-RS {@link Response} carries an entity the declared type does not tell: it is described by a defined,
 * minimal schema (an opaque object), never by its getters, and publishes no component (§3.11;
 * vidocq-workspace#40, item 4).
 */
class ResponseSchemaTest {

    private final SchemaRegistry registry = new SchemaRegistry();
    private final SchemaGenerator generator = new SchemaGenerator(registry);

    @Test
    void response_isAnOpaqueObject() {
        var schema = generator.generate(Response.class);

        assertEquals(List.of(SchemaType.OBJECT), schema.getType());
        assertNull(schema.getRef());
        assertTrue(schema.getProperties() == null || schema.getProperties().isEmpty());
    }

    @Test
    void response_publishesNoComponent() {
        generator.generate(Response.class);

        // Response, URI, Locale, MediaType… used to be registered from its getters.
        assertTrue(registry.snapshot().isEmpty(), registry.snapshot().keySet().toString());
    }

    @Test
    void responseInsideAList_publishesNoComponent() throws Exception {
        var type = Holder.class.getDeclaredField("responses").getGenericType();

        var schema = generator.generate(type);

        assertEquals(List.of(SchemaType.ARRAY), schema.getType());
        assertEquals(List.of(SchemaType.OBJECT), schema.getItems().getType());
        assertTrue(registry.snapshot().isEmpty(), registry.snapshot().keySet().toString());
    }

    @Test
    void aSubclassOfResponse_isTheSameOpaqueObject() {
        var schema = generator.generate(CustomResponse.class);

        assertEquals(List.of(SchemaType.OBJECT), schema.getType());
        assertTrue(registry.snapshot().isEmpty(), registry.snapshot().keySet().toString());
    }

    @Test
    void configuredOverride_stillWinsOverTheDefault() {
        // mp.openapi.schema.<FQCN> is pre-registered in the registry and wins even here (§4.1).
        var override = org.eclipse.microprofile.openapi.OASFactory.createSchema().addType(SchemaType.STRING);
        registry.reserve(Response.class, "Response");
        registry.publish("Response", override);

        var schema = generator.generate(Response.class);

        assertEquals("#/components/schemas/Response", schema.getRef());
    }

    @SuppressWarnings("unused")
    static final class Holder {
        List<Response> responses;
    }

    abstract static class CustomResponse extends Response {
    }
}
