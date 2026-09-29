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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Gizmo;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Level;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Part;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Entities read and built through Mansart's model and its handles (spec §5). */
class EntityJsonTest {

    /** The schema of a Gizmo, in model order, none required. */
    static final String GIZMO_SCHEMA = "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"},"
            + "\"name\":{\"type\":\"string\"},\"stock\":{\"type\":\"integer\"},"
            + "\"level\":{\"type\":\"string\",\"enum\":[\"LOW\",\"HIGH\"]},"
            + "\"due\":{\"type\":\"string\",\"format\":\"date\"},\"price\":{\"type\":\"number\"}}}";

    private final EntityJson entities = RunFixtures.entities();

    @Test
    void anEntityIsAnObjectOfItsColumnsNoneRequired() {
        assertEquals(GIZMO_SCHEMA, Json.write(entities.schema(Gizmo.class)));
    }

    @Test
    void aReferenceTakesTheReferencedIdAndAJoinedAttributeIsLeftOut() {
        assertEquals("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"},"
                + "\"gizmo\":{\"type\":\"integer\"},\"label\":{\"type\":\"string\"}}}",
                Json.write(entities.schema(Part.class)));
    }

    @Test
    void builtWithItsConstructorThenEachPropertyPresent() throws Exception {
        Gizmo gizmo = (Gizmo) entities.fromJson(Gizmo.class, Json.parse("{\"name\":\"bolt\",\"stock\":3,"
                + "\"level\":\"HIGH\",\"due\":\"2026-10-01\",\"price\":2.50}"), "gizmo");

        assertNull(gizmo.id(), "an absent id keeps the constructor's value, so that a generated one is generated");
        assertEquals("bolt", gizmo.name());
        assertEquals(3, gizmo.stock());
        assertEquals(Level.HIGH, gizmo.level());
        assertEquals(LocalDate.of(2026, 10, 1), gizmo.due());
        assertEquals(new BigDecimal("2.50"), gizmo.price());
    }

    @Test
    void aReferenceIsBuiltFromItsId() throws Exception {
        Part part = (Part) entities.fromJson(Part.class, Json.parse("{\"gizmo\":7,\"label\":\"left\"}"), "part");

        assertEquals(7L, part.gizmo().id());
        assertEquals("left", part.label());
    }

    @Test
    void eachFailureNamesItsProperty() {
        assertEquals("gizmo.colour: unknown property", refusal("{\"colour\":\"red\"}"));
        assertEquals("gizmo.stock: null is not allowed for int", refusal("{\"stock\":null}"));
        assertEquals("gizmo.due: not an ISO date", refusal("{\"due\":\"tomorrow\"}"));
        assertEquals("gizmo: not a JSON object", refusal("[]"));
    }

    private String refusal(String json) {
        return assertThrows(ArgumentException.class,
                () -> entities.fromJson(Gizmo.class, Json.parse(json), "gizmo")).getMessage();
    }

    @Test
    void toJsonInModelOrderAReferenceAsItsId() {
        Gizmo gizmo = RunFixtures.gizmo(7L, "bolt", 3, Level.LOW, LocalDate.of(2026, 10, 1), new BigDecimal("2.50"));

        assertEquals("{\"id\":7,\"name\":\"bolt\",\"stock\":3,\"level\":\"LOW\",\"due\":\"2026-10-01\","
                + "\"price\":2.50}", Json.write(entities.toJson(gizmo)));
        assertEquals("{\"id\":5,\"gizmo\":7,\"label\":\"left\"}",
                Json.write(entities.toJson(RunFixtures.part(5L, gizmo, "left"))));
    }

    @Test
    void theNamesAnExportWritesAreEveryReadableAttributeButAJoinedOne() {
        assertEquals(List.of("id", "name", "stock", "level", "due", "price"), entities.names(Gizmo.class));
        assertEquals(List.of("id", "gizmo", "label"), entities.names(Part.class), "the joined name left out");
        assertEquals(List.of("id", "year", "label"), entities.names(RunFixtures.Slot.class),
                "a Year is read, as its text");
        assertEquals(7L, entities.idOf(RunFixtures.gizmo(7L, "bolt", 3, Level.LOW, null, null)));
    }

    @Test
    void anImportSetsTheAttributesThatConvertAReferenceByItsId() throws ArgumentException {
        assertEquals(List.of("id", "name", "stock", "level", "due", "price"), entities.columns(Gizmo.class));
        assertEquals(List.of("id", "gizmo", "label"), entities.columns(Part.class));
        assertEquals(List.of("id", "label"), entities.columns(RunFixtures.Slot.class), "a Year cannot be converted");

        EntityJson.TextRows parts = entities.textRows(Part.class, List.of("label", "gizmo"));

        assertEquals("{\"id\":null,\"gizmo\":7,\"label\":\"left\"}",
                Json.write(entities.toJson(parts.build(Arrays.asList("left", "7")))));
        assertEquals("{\"id\":null,\"gizmo\":null,\"label\":null}",
                Json.write(entities.toJson(parts.build(Arrays.asList(null, null)))));
        assertEquals("gizmo: not an integer",
                assertThrows(ArgumentException.class, () -> parts.build(Arrays.asList("x", "seven"))).getMessage());
        assertEquals("year: not an attribute that can be set", assertThrows(ArgumentException.class,
                () -> entities.textRows(RunFixtures.Slot.class, List.of("year"))).getMessage());
    }

    @Test
    void onlyTheCatalogueEntitiesAreEntities() {
        assertTrue(entities.isEntity(Gizmo.class));
        assertFalse(entities.isEntity(String.class));
        assertFalse(entities.isEntity(null));
    }
}
