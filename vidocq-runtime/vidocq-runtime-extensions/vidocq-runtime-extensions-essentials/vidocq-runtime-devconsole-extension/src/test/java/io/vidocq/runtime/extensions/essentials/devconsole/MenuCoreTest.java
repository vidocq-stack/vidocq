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
package io.vidocq.runtime.extensions.essentials.devconsole;

import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The menu a rows result offers on its selected rows, in the page's pure half: the entries read with the rows, the
 * selected values written as query literals, and the arguments of an entry filled with them.
 */
class MenuCoreTest {

    private static final String DELETE = "{\"label\":\"Delete these rows\",\"action\":\"jpql\",\"arguments\":"
            + "{\"request\":{\"query\":\"DELETE FROM Note e WHERE e.id IN (${id})\",\"why\":[\"${name} ${nope}\"]}}}";
    private static final String BODY = "{\"columns\":[{\"name\":\"id\",\"type\":\"BIGINT\"},{\"name\":\"name\","
            + "\"type\":\"\"},{\"name\":\"big\",\"type\":\"\"},{\"name\":\"done\",\"type\":\"\"}],\"menu\":[" + DELETE
            + "],\"rows\":[[1,\"O'Brien\",12345678901234567890,true],[2,null,3.50,false],[3,\"b\",4,null]],"
            + "\"more\":false}";

    private static PageCore core;

    @BeforeAll
    static void loadTheModule() {
        core = new PageCore();
    }

    @AfterAll
    static void closeTheEngine() {
        core.close();
    }

    private static String menu(String body) {
        return core.json(core.eval("(rows) => rows.menu", core.run("readRows", body)));
    }

    /** The arguments the first entry of BODY fills for the rows at {@code picked}, as JSON. */
    private static String filled(String picked) {
        Value rows = core.run("readRows", BODY);
        Value entry = core.eval("(rows) => rows.menu[0]", rows);
        return core.json(core.run("menuArguments", entry, BODY, core.parse(picked)));
    }

    /** The literal of the cell at {@code column} of the row at {@code row} of BODY, as the menu writes it. */
    private static String literal(int row, String column) {
        String query = "{\"q\":\"${" + column + "}\"}";
        String body = BODY.replace(DELETE, "{\"label\":\"x\",\"action\":\"jpql\",\"arguments\":" + query + "}");
        Value entry = core.eval("(rows) => rows.menu[0]", core.run("readRows", body));
        return core.run("menuArguments", entry, body, core.parse("[" + row + "]")).getMember("q").asString();
    }

    @Test
    void aRowsBodyKeepsItsMenu() {
        assertEquals("[" + DELETE + "]", menu(BODY));
    }

    @Test
    void anInvalidEntryIsLeftOut() {
        String odd = BODY.replace("\"menu\":[" + DELETE + "]", "\"menu\":[{\"label\":5,\"action\":\"jpql\","
                + "\"arguments\":{}},{\"label\":\"x\",\"action\":\"Run JPQL\",\"arguments\":{}},{\"label\":\"y\","
                + "\"action\":\"jpql\",\"arguments\":[1]}," + DELETE + ",\"jpql\"]");

        assertEquals("[" + DELETE + "]", menu(odd));
        assertEquals("[]", menu(BODY.replace("\"menu\":[" + DELETE + "]", "\"menu\":{}")));
    }

    @Test
    void noMenuReadsAsAnEmptyOne() {
        assertEquals("[]", menu(BODY.replace("\"menu\":[" + DELETE + "],", "")));
    }

    @Test
    void aStringIsQuotedWithItsQuotesDoubled() {
        assertEquals("'O''Brien'", literal(0, "name"));
        assertEquals("'b'", literal(2, "name"));
    }

    @Test
    void aNumberKeepsItsDigits() {
        assertEquals("12345678901234567890", literal(0, "big"), "past 2^53, never rounded");
        assertEquals("3.50", literal(1, "big"), "as the body writes it");
        assertEquals("1", literal(0, "id"));
    }

    @Test
    void nullAndBooleansAreKeywords() {
        assertEquals("NULL", literal(1, "name"));
        assertEquals("TRUE", literal(0, "done"));
        assertEquals("FALSE", literal(1, "done"));
        assertEquals("NULL", literal(2, "done"));
    }

    @Test
    void theSelectedRowsFillThePlaceholderInRowOrder() {
        assertEquals("{\"request\":{\"query\":\"DELETE FROM Note e WHERE e.id IN (1, 3)\",\"why\":[\"'O''Brien', "
                + "'b' ${nope}\"]}}", filled("[2,0]"));
    }

    @Test
    void anUnknownPlaceholderStays() {
        assertEquals("{\"request\":{\"query\":\"DELETE FROM Note e WHERE e.id IN (2)\",\"why\":[\"NULL ${nope}\"]}}",
                filled("[1]"));
    }

    @Test
    void theEntryItselfIsLeftAsItWas() {
        Value rows = core.run("readRows", BODY);
        Value entry = core.eval("(rows) => rows.menu[0]", rows);
        core.run("menuArguments", entry, BODY, core.parse("[0]"));

        assertEquals("[" + DELETE + "]", core.json(core.eval("(rows) => rows.menu", rows)), "a copy is filled");
    }
}
