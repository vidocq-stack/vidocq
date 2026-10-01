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

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A result of rows in the editor's pure half, {@code editor-core.js}, run by GraalJS as the ES module the page loads
 * (SQL spec §2.2): the check of its body's shape, and its rows as CSV.
 */
class RowsCoreTest {

    private static final String BODY = "{\"columns\":[{\"name\":\"id\",\"type\":\"BIGINT\"},{\"name\":\"title\","
            + "\"type\":\"VARCHAR(200)\"},{\"name\":\"done\",\"type\":\"\"}],\"rows\":[[1,\"a, \\\"b\\\"\",true],"
            + "[2.5,\"\",null],[3,\"two\\r\\nlines\",false]],\"more\":true}";

    private static Context context;
    private static Value json;
    private static Value readRows;
    private static Value rowsCsv;

    @BeforeAll
    static void loadTheModule() {
        context = Context.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .option("js.esm-eval-returns-exports", "true")
                .build();
        Value exports = context.eval(Source.newBuilder("js", resource("editor-core.js"), "editor-core.mjs")
                .mimeType("application/javascript+module").buildLiteral());
        json = context.eval("js", "JSON");
        readRows = exports.getMember("readRows");
        rowsCsv = exports.getMember("rowsCsv");
    }

    @AfterAll
    static void closeTheEngine() {
        context.close();
    }

    private static String resource(String name) {
        String path = DevConsoleExtension.PAGE_RESOURCES + "/" + name;
        try (InputStream in = RowsCoreTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "the page has no " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What readRows makes of {@code text}, as JSON, or "null". */
    private static String read(String text) {
        return json.invokeMember("stringify", readRows.execute(text)).asString();
    }

    private static String csv(String text) {
        return rowsCsv.execute(readRows.execute(text)).asString();
    }

    @Test
    void aBodyOfRowsIsItsColumnsItsRowsAndItsMoreFlag() {
        assertEquals("{\"columns\":[{\"name\":\"id\",\"type\":\"BIGINT\"},{\"name\":\"title\",\"type\":"
                + "\"VARCHAR(200)\"},{\"name\":\"done\",\"type\":\"\"}],\"rows\":[[1,\"a, \\\"b\\\"\",true],"
                + "[2.5,\"\",null],[3,\"two\\r\\nlines\",false]],\"more\":true}", read(BODY));
        assertEquals("{\"columns\":[],\"rows\":[],\"more\":false}",
                read("{\"more\":false,\"rows\":[],\"columns\":[],\"other\":1}"), "another member is left out");
    }

    @Test
    void aBodyOfAnotherShapeIsNoRows() {
        for (String odd : new String[] {"", "[]", "nope", "{\"columns\":[],\"rows\":[]}",
                "{\"columns\":[],\"rows\":[],\"more\":\"yes\"}", "{\"columns\":{},\"rows\":[],\"more\":false}",
                "{\"columns\":[{\"name\":\"a\"}],\"rows\":[],\"more\":false}",
                "{\"columns\":[{\"name\":1,\"type\":\"\"}],\"rows\":[],\"more\":false}",
                "{\"columns\":[{\"name\":\"a\",\"type\":\"\"}],\"rows\":[[1,2]],\"more\":false}",
                "{\"columns\":[{\"name\":\"a\",\"type\":\"\"}],\"rows\":[[{\"x\":1}]],\"more\":false}",
                "{\"columns\":[{\"name\":\"a\",\"type\":\"\"}],\"rows\":[[[1]]],\"more\":false}",
                "{\"columns\":[{\"name\":\"a\",\"type\":\"\"}],\"rows\":[1],\"more\":false}"}) {
            assertEquals("null", read(odd), odd);
        }
    }

    @Test
    void theCsvIsTheHeaderThenEachRowEveryLineEndedByCrLf() {
        assertEquals("id,title,done\r\n1,\"a, \"\"b\"\"\",true\r\n2.5,\"\",\r\n3,\"two\r\nlines\",false\r\n",
                csv(BODY));
    }

    @Test
    void nullIsAnEmptyFieldAndAnEmptyStringTwoQuotes() {
        String csv = csv("{\"columns\":[{\"name\":\"a\",\"type\":\"\"},{\"name\":\"\",\"type\":\"\"}],"
                + "\"rows\":[[null,\"\"],[\"x\",null]],\"more\":false}");

        assertEquals("a,\"\"\r\n,\"\"\r\nx,\r\n", csv);
    }

    @Test
    void aFieldIsQuotedOnlyWhenItHoldsACommaAQuoteOrALineEnd() {
        String csv = csv("{\"columns\":[{\"name\":\"t\",\"type\":\"\"}],\"rows\":[[\" spaced \"],[\"semi;colon\"],"
                + "[\"line\\nend\"],[\"cr\\rend\"],[\"=1+1\"]],\"more\":false}");

        assertEquals("t\r\n spaced \r\nsemi;colon\r\n\"line\nend\"\r\n\"cr\rend\"\r\n=1+1\r\n", csv);
        assertTrue(csv.endsWith("\r\n"), "every line ends with CRLF, the last one too");
    }
}
