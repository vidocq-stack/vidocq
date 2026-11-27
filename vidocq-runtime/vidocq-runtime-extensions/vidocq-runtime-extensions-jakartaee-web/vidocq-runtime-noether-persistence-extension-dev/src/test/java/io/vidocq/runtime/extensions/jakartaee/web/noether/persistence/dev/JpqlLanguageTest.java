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
import io.vidocq.runtime.spi.devconsole.PanelLanguage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev.ModelGraphTest.UNIT;
import static io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev.ModelGraphTest.attribute;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The JPQL language of the query editor: SQL's aliases, JPQL's words, and the entities of every unit. */
class JpqlLanguageTest {

    static Map<?, ?> member(Object object, String... path) {
        Object at = object;
        for (String name : path) {
            at = ((Map<?, ?>) at).get(name);
        }
        return (Map<?, ?>) at;
    }

    @Test
    void theArgumentJsonReadsFalseAsABoolean() {
        assertEquals(List.of(false, true), Json.parse("[false,true]"));
    }

    @Test
    void theDialectHasAliasesAndNoSelf() {
        Map<?, ?> language = (Map<?, ?>) Json.parse(JpqlLanguage.json(List.of(UNIT)));

        assertEquals("query", language.get("mode"));
        Map<?, ?> dialect = member(language, "dialect");
        assertEquals(true, dialect.get("aliases"));
        assertTrue(dialect.containsKey("self"));
        assertNull(dialect.get("self"));
        assertEquals(List.of("FROM", "JOIN", "UPDATE"), dialect.get("targetAfter"));
        assertTrue(((List<?>) dialect.get("keywords")).contains("JOIN"));
        assertTrue(((List<?>) dialect.get("functions")).contains("SIZE"));
    }

    @Test
    void eachEntityIsATargetWithItsAttributes() {
        Map<?, ?> language = (Map<?, ?>) Json.parse(JpqlLanguage.json(List.of(UNIT)));

        assertEquals("table NOTE", member(language, "targets", "Note").get("detail"));
        assertEquals("abstract entity, table ANIMAL", member(language, "targets", "Animal").get("detail"));
        assertEquals("string", member(language, "targets", "Note", "attributes", "title").get("type"));
        assertEquals("String, basic, TITLE", member(language, "targets", "Note", "attributes", "title")
                .get("detail"));
        Map<?, ?> author = member(language, "targets", "Note", "attributes", "author");
        assertEquals("Author", author.get("target"));
        assertEquals("integer", author.get("type"));
        assertFalse(member(language, "targets", "Note", "attributes", "printer").containsKey("target"),
                "Printer is no entity of the unit");
        assertEquals("Tag", member(language, "targets", "Note", "attributes", "tags").get("target"));
    }

    @Test
    void theUnitsShareOneLanguageTheFirstNamingAnEntityWinning() {
        Entity other = new Entity("Note", "com.other.Note", "OTHER_NOTE", null, Inheritance.NONE, false, List.of(
                attribute("id", "java.util.UUID", "com.other.Note", Kind.ID, List.of("ID"), null, null, null)));
        Entity entry = new Entity("Entry", "com.acme.Entry", "ENTRY", null, Inheritance.NONE, false, List.of());
        UnitDescription ledger = new UnitDescription("ledger", List.of(other, entry), List.of(), List.of(),
                List.of());

        Map<?, ?> language = (Map<?, ?>) Json.parse(JpqlLanguage.json(List.of(UNIT, ledger)));

        assertEquals("table NOTE", member(language, "targets", "Note").get("detail"));
        assertEquals("table ENTRY", member(language, "targets", "Entry").get("detail"));
    }

    @Test
    void itIsALanguageThePanelMayOffer() {
        assertDoesNotThrow(() -> new PanelLanguage(JpqlLanguage.ID, JpqlLanguage.json(List.of(UNIT))));
    }
}
