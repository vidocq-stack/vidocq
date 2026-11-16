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

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A statement of the JPQL tab: its query, its named parameters, and the values they take. */
class StatementTest {

    enum Status { OPEN, CLOSED }

    @Test
    void paramsAreTheJsonValues() throws Refused {
        Statement statement = Statement.read("{\"query\": \" SELECT n FROM Note n WHERE n.id = :id \","
                + " \"params\": {\"id\": 3, \"title\": \"x\"}}");

        assertEquals("SELECT n FROM Note n WHERE n.id = :id", statement.query());
        assertEquals(Map.of("id", 3L, "title", "x"), statement.params());
    }

    @Test
    void paramsMayBeTheTextOfAnObjectOrBlank() throws Refused {
        assertEquals(Map.of("id", 3L), Statement.read("{\"query\": \"FROM Note\", \"params\": \"{\\\"id\\\": 3}\"}")
                .params());
        assertEquals(Map.of(), Statement.read("{\"query\": \"FROM Note\", \"params\": \"  \"}").params());
    }

    @Test
    void aDecimalIsABigDecimalAndAListStaysAList() throws Refused {
        Statement statement = Statement.read("{\"query\": \"FROM Note\", \"params\": {\"price\": 2.5, \"ids\": [1, 2],"
                + " \"done\": true, \"none\": null}}");

        assertEquals(new BigDecimal("2.5"), statement.params().get("price"));
        assertEquals(List.of(1L, 2L), statement.params().get("ids"));
        assertEquals(true, statement.params().get("done"));
        assertTrue(statement.params().containsKey("none"));
    }

    @Test
    void aStatementIsAnObjectWithAQueryAndItsParams() {
        assertEquals("statement: not a JSON object", refused("[1]"));
        assertEquals("statement: Unexpected end of the text at 1", refused("{"));
        assertEquals("query: missing", refused("{\"params\": {}}"));
        assertEquals("query: missing", refused("{\"query\": \"  \"}"));
        assertEquals("query: not a string", refused("{\"query\": 3}"));
        assertEquals("limit: unknown argument", refused("{\"query\": \"FROM Note\", \"limit\": 3}"));
        assertEquals("params: not a JSON object", refused("{\"query\": \"FROM Note\", \"params\": [1]}"));
        assertEquals("params.p: not a value or a list of values",
                refused("{\"query\": \"FROM Note\", \"params\": {\"p\": {\"a\": 1}}}"));
        assertEquals("params.p: not a value or a list of values",
                refused("{\"query\": \"FROM Note\", \"params\": {\"p\": [[1]]}}"));
    }

    @Test
    void anUpdateOrADeleteIsAWrite() throws Refused {
        assertTrue(Statement.read("{\"query\": \"update Note n SET n.title = 'x'\"}").isWrite());
        assertTrue(Statement.read("{\"query\": \"DELETE FROM Note n\"}").isWrite());
        assertFalse(Statement.read("{\"query\": \"SELECT n FROM Note n\"}").isWrite());
        assertFalse(Statement.read("{\"query\": \"FROM Note\"}").isWrite());
        assertFalse(Statement.read("{\"query\": \"UPDATED\"}").isWrite());
    }

    @Test
    void aValueTakesTheTypeTheQueryDeclares() {
        assertEquals(LocalDate.of(2026, 11, 16), Statement.bind("since", "2026-11-16", LocalDate.class));
        assertEquals(LocalDateTime.of(2026, 11, 16, 10, 0), Statement.bind("at", "2026-11-16T10:00",
                LocalDateTime.class));
        assertEquals(Status.OPEN, Statement.bind("status", "OPEN", Status.class));
        assertEquals(List.of(Status.OPEN, Status.CLOSED), Statement.bind("in", List.of("OPEN", "CLOSED"),
                Status.class));
        assertEquals(3, Statement.bind("n", 3L, Integer.class));
        assertEquals(12L, Statement.bind("n", "12", Long.class));
        assertEquals(new BigDecimal("2.50"), Statement.bind("n", "2.50", BigDecimal.class));
        UUID id = UUID.fromString("4f1c2a52-1a43-4d5e-9b62-05b0c5b3e1a7");
        assertEquals(id, Statement.bind("id", id.toString(), UUID.class));
        assertEquals("x", Statement.bind("x", "x", null));
        assertEquals("x", Statement.bind("x", "x", Object.class));
        assertEquals(null, Statement.bind("x", null, Long.class));
    }

    @Test
    void aValueThatCannotTakeTheTypeSaysWhich() {
        IllegalArgumentException wrong = assertThrows(IllegalArgumentException.class,
                () -> Statement.bind("since", "yesterday", LocalDate.class));
        assertEquals("parameter since: \"yesterday\" is not a LocalDate", wrong.getMessage());
        IllegalArgumentException state = assertThrows(IllegalArgumentException.class,
                () -> Statement.bind("status", "LOST", Status.class));
        assertEquals("parameter status: \"LOST\" is not a Status", state.getMessage());
    }

    private static String refused(String json) {
        return assertThrows(Refused.class, () -> Statement.read(json)).getMessage();
    }
}
