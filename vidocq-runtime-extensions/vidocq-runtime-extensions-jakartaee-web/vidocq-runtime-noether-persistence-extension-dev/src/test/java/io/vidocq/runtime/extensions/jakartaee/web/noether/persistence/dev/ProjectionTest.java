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
package io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev;

import io.vidocq.noether.jpa.core.spi.UnitDescription;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Attribute.Kind;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Entity;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Inheritance;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult.Column;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev.ModelGraphTest.UNIT;
import static io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev.ModelGraphTest.attribute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The projection of an entity: a column per value it holds, read by JPQL without reflection. */
class ProjectionTest {

    static Entity entity(UnitDescription unit, String name) {
        return unit.entities().stream().filter(e -> e.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void aNoteShowsItsValuesItsAuthorsIdAndItsAddress() {
        Projection note = Projection.of(entity(UNIT, "Note"), UNIT);

        assertEquals("SELECT e.id, e.title, j1.id, e.address.street, e.address.city FROM Note e"
                + " LEFT JOIN e.author j1", note.select());
        assertEquals(List.of(new Column("id", "integer"), new Column("title", "string"),
                new Column("author.id", "integer"), new Column("address.street", "string"),
                new Column("address.city", "string")), note.columns());
        assertEquals("e.id", note.idPath());
        assertEquals(0, note.idColumn());
        assertEquals(List.of("e.id"), note.idPaths());
    }

    @Test
    void aSubclassShowsTheValuesItInherits() {
        assertEquals("SELECT e.id, e.breed FROM Dog e", Projection.of(entity(UNIT, "Dog"), UNIT).select());
    }

    @Test
    void aSelfReferenceAndACollectionAreJoinedOrLeftOut() {
        assertEquals("SELECT e.id, j1.id FROM Employee e LEFT JOIN e.manager j1",
                Projection.of(entity(UNIT, "Employee"), UNIT).select());
        assertEquals("SELECT e.id FROM Author e", Projection.of(entity(UNIT, "Author"), UNIT).select());
    }

    @Test
    void aCompositeIdHasNoIdPath() {
        Entity line = new Entity("Line", "com.acme.Line", "LINE", null, Inheritance.NONE, false, List.of(
                attribute("key", "com.acme.LineKey", "com.acme.Line", Kind.EMBEDDED_ID, List.of("ORDER_ID", "NO"),
                        "com.acme.LineKey", null, null),
                attribute("amount", "java.math.BigDecimal", "com.acme.Line", Kind.BASIC, List.of("AMOUNT"), null,
                        null, null)));
        UnitDescription unit = new UnitDescription("orders", List.of(line), List.of(), List.of(), List.of());

        Projection projection = Projection.of(line, unit);

        assertEquals("SELECT e.key, e.amount FROM Line e", projection.select());
        assertEquals(List.of(new Column("key", "string"), new Column("amount", "number")), projection.columns());
        assertNull(projection.idPath());
        assertEquals(-1, projection.idColumn());
        assertEquals(List.of(), projection.idPaths(), "an embedded id whose embeddable is unknown sorts nothing");
    }

    @Test
    void aKnownEmbeddedIdIsAColumnPerValueAndSortsThePage() {
        UnitDescription unit = ExploreActionsTest.LINES;

        Projection projection = Projection.of(unit.entities().getFirst(), unit);

        assertEquals("SELECT e.key.orderId, e.key.no, e.amount FROM Line e", projection.select());
        assertEquals(List.of("e.key.orderId", "e.key.no"), projection.idPaths());
        assertNull(projection.idPath());
    }
}
