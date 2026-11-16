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
import io.vidocq.noether.jpa.core.spi.UnitDescription.Attribute;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Attribute.Kind;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Embeddable;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Entity;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Inheritance;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult.Edge;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult.Fact;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult.Node;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Model tab's graph of a unit: its boxes, its lines and their labels, read from a {@link UnitDescription}. */
class ModelGraphTest {

    static final String BASE = "com.acme.Base";
    static final String ADDRESS = "com.acme.Address";

    static Attribute attribute(String name, String type, String declaring, Kind kind, List<String> columns,
                               String target, String mappedBy, String joinTable) {
        return new Attribute(name, type, declaring, kind, columns, target, mappedBy, joinTable);
    }

    static Attribute id(String declaring) {
        return attribute("id", "java.lang.Long", declaring, Kind.ID, List.of("ID"), null, null, null);
    }

    /** Note (on the mapped superclass Base), Author, Tag, Employee, the JOINED pair Animal and Dog, and Address. */
    static final UnitDescription UNIT = new UnitDescription("notes", List.of(
            new Entity("Note", "com.acme.Note", "NOTE", null, Inheritance.NONE, false, List.of(
                    id(BASE),
                    attribute("title", "java.lang.String", "com.acme.Note", Kind.BASIC, List.of("TITLE"), null, null,
                            null),
                    attribute("author", "com.acme.Author", "com.acme.Note", Kind.MANY_TO_ONE, List.of("AUTHOR_ID"),
                            "Author", null, null),
                    attribute("tags", "java.util.Set", "com.acme.Note", Kind.MANY_TO_MANY, List.of(), "Tag", null,
                            "NOTE_TAG"),
                    attribute("address", ADDRESS, "com.acme.Note", Kind.EMBEDDED, List.of("STREET", "CITY"), ADDRESS,
                            null, null),
                    attribute("printer", "com.other.Printer", "com.acme.Note", Kind.MANY_TO_ONE,
                            List.of("PRINTER_ID"), "Printer", null, null))),
            new Entity("Author", "com.acme.Author", "AUTHOR", null, Inheritance.NONE, false, List.of(
                    id("com.acme.Author"),
                    attribute("notes", "java.util.List", "com.acme.Author", Kind.ONE_TO_MANY, List.of(), "Note",
                            "author", null))),
            new Entity("Tag", "com.acme.Tag", "TAG", null, Inheritance.NONE, false, List.of(id("com.acme.Tag"))),
            new Entity("Employee", "com.acme.Employee", "EMPLOYEE", null, Inheritance.NONE, false, List.of(
                    id("com.acme.Employee"),
                    attribute("manager", "com.acme.Employee", "com.acme.Employee", Kind.MANY_TO_ONE,
                            List.of("MANAGER_ID"), "Employee", null, null))),
            new Entity("Animal", "com.acme.Animal", "ANIMAL", null, Inheritance.JOINED, true, List.of(
                    id("com.acme.Animal"))),
            new Entity("Dog", "com.acme.Dog", "DOG", "Animal", Inheritance.JOINED, false, List.of(
                    id("com.acme.Animal"),
                    attribute("breed", "java.lang.String", "com.acme.Dog", Kind.BASIC, List.of("BREED"), null, null,
                            null)))),
            List.of(new Embeddable(ADDRESS, List.of(
                    attribute("street", "java.lang.String", ADDRESS, Kind.BASIC, List.of("STREET"), null, null, null),
                    attribute("city", "java.lang.String", ADDRESS, Kind.BASIC, List.of("CITY"), null, null, null)))),
            List.of(), List.of());

    static Node node(String id) {
        return ModelGraph.nodes(UNIT).stream().filter(n -> n.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no node " + id + " in " + ModelGraph.nodes(UNIT)));
    }

    static List<Edge> edges(String from, String to) {
        return ModelGraph.edges(UNIT).stream().filter(e -> e.from().equals(from) && e.to().equals(to)).toList();
    }

    @Test
    void aSubclassPointsAtItsParentWhichSaysItsInheritance() {
        assertEquals(List.of(new Edge("entity:Dog", "entity:Animal", "extends", true, false)),
                edges("entity:Dog", "entity:Animal"));
        Node animal = node("entity:Animal");
        assertEquals("abstract entity", animal.kind());
        assertTrue(animal.facts().contains(new Fact("inheritance", "JOINED")), animal.facts().toString());
        assertFalse(node("entity:Dog").facts().stream().anyMatch(f -> f.name().equals("id")),
                "an inherited attribute is listed on the box that declares it");
    }

    @Test
    void aMappedSuperclassIsABoxOfItsOwn() {
        Node base = node("class:" + BASE);
        assertEquals("Base", base.label());
        assertEquals("mapped superclass", base.kind());
        assertEquals(List.of(new Fact("id", "Long, id, ID")), base.facts());
        assertEquals(List.of(new Edge("entity:Note", "class:" + BASE, "extends", true, false)),
                edges("entity:Note", "class:" + BASE));
    }

    @Test
    void aBidirectionalRelationIsOneLineFromItsOwner() {
        assertEquals(List.of(new Edge("entity:Note", "entity:Author", "author *..1 / notes", false, false)),
                edges("entity:Note", "entity:Author"));
        assertEquals(List.of(), edges("entity:Author", "entity:Note"));
        assertEquals(List.of(new Edge("entity:Note", "entity:Tag", "tags *..*", false, false)),
                edges("entity:Note", "entity:Tag"));
    }

    @Test
    void anEmbeddedValueIsDashed() {
        assertEquals(List.of(new Edge("entity:Note", "embeddable:" + ADDRESS, "address", false, true)),
                edges("entity:Note", "embeddable:" + ADDRESS));
        Node address = node("embeddable:" + ADDRESS);
        assertEquals("Address", address.label());
        assertEquals("embeddable", address.kind());
        assertEquals(List.of(new Fact("street", "String, basic, STREET"), new Fact("city", "String, basic, CITY")),
                address.facts());
    }

    @Test
    void aSelfReferenceIsALoop() {
        assertEquals(List.of(new Edge("entity:Employee", "entity:Employee", "manager *..1", false, false)),
                edges("entity:Employee", "entity:Employee"));
    }

    @Test
    void theFactsListTheTableAndEachAttributeWithItsColumns() {
        assertEquals(List.of(
                new Fact("table", "NOTE"),
                new Fact("title", "String, basic, TITLE"),
                new Fact("author", "Author, many to one, AUTHOR_ID, owner"),
                new Fact("tags", "Set, many to many, owner, join table NOTE_TAG"),
                new Fact("address", "Address, embedded, STREET CITY"),
                new Fact("printer", "Printer, many to one, PRINTER_ID, owner")), node("entity:Note").facts());
        assertEquals(new Fact("notes", "List, one to many, mapped by author"), node("entity:Author").facts().get(2));
    }

    @Test
    void aRelationToAnEntityOutsideTheUnitDrawsNoLine() {
        assertTrue(ModelGraph.edges(UNIT).stream().noneMatch(e -> e.to().contains("Printer")));
    }

    @Test
    void theSummaryCountsEntitiesAndRelations() {
        ActionResult result = ModelGraph.of(UNIT);

        assertEquals("6 entities, 3 relations", result.summary());
        assertEquals(ActionResult.GRAPH, result.contentType());
        assertFalse(result.error());
    }

    @Test
    void aUnitPastWhatAGraphHoldsAnswersAnError() {
        List<Entity> many = new ArrayList<>();
        IntStream.range(0, ActionResult.MAX_NODES + 1).forEach(i -> many.add(new Entity("E" + i, "com.acme.E" + i,
                "E" + i, null, Inheritance.NONE, false, List.of(id("com.acme.E" + i)))));
        UnitDescription huge = new UnitDescription("huge", many, List.of(), List.of(), List.of());

        ActionResult result = ModelGraph.of(huge);

        assertTrue(result.error());
        assertEquals("501 boxes and 0 lines: past the 500 boxes and 2000 lines a graph draws", result.summary());
    }
}
