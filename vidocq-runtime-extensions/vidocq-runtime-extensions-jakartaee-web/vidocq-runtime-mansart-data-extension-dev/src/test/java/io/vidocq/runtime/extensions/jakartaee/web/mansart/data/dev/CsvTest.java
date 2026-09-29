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

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The CSV the JDQL tab writes and reads (CSV spec §5): RFC 4180, null apart from the empty text. */
class CsvTest {

    private static List<String> row(String... fields) {
        return Arrays.asList(fields);
    }

    private static List<List<String>> fields(List<Csv.Record> records) {
        return records.stream().map(Csv.Record::fields).toList();
    }

    private static List<Integer> lines(List<Csv.Record> records) {
        return records.stream().map(Csv.Record::line).toList();
    }

    private static String malformed(String text) {
        return assertThrows(Csv.Malformed.class, () -> Csv.read(text, ',')).getMessage();
    }

    @Test
    void aFieldIsQuotedWhenItHoldsTheSeparatorAQuoteALineEndOrNothing() {
        assertEquals("plain", Csv.field("plain", ','));
        assertEquals("\"a,b\"", Csv.field("a,b", ','));
        assertEquals("a,b", Csv.field("a,b", ';'), "a comma is text when ; separates");
        assertEquals("\"a;b\"", Csv.field("a;b", ';'));
        assertEquals("\"say \"\"hi\"\"\"", Csv.field("say \"hi\"", ','));
        assertEquals("\"two\nlines\"", Csv.field("two\nlines", ','));
        assertEquals("\"cr\rhere\"", Csv.field("cr\rhere", ','));
        assertEquals("\"\"", Csv.field("", ','), "the empty text");
        assertEquals("", Csv.field(null, ','), "null: nothing at all");
    }

    @Test
    void theWriterEndsEachRecordWithCrLf() {
        Csv.Writer out = new Csv.Writer(',', 1000);

        assertTrue(out.add(row("id", "name")));
        assertTrue(out.add(row("1", null)));
        assertTrue(out.add(row("2", "")));

        assertEquals("id,name\r\n1,\r\n2,\"\"\r\n", out.text());
        assertEquals(3, out.records());
        assertEquals(out.text().length(), out.bytes());
    }

    @Test
    void theWriterRefusesARecordThatWouldPassItsLimitInUtf8Bytes() {
        Csv.Writer out = new Csv.Writer(',', 10);

        assertTrue(out.add(row("éé")), "2 × 2 + 2 = 6 bytes");
        assertFalse(out.add(row("ééé")), "6 + 8 = 14 bytes, past 10 although 11 characters would be too");
        assertEquals("éé\r\n", out.text(), "a refused record leaves the text as it was");
        assertEquals(6, out.bytes());
        assertEquals(1, out.records());
        assertTrue(out.add(row("ab")), "6 + 4 = 10 bytes: the limit itself is allowed");
    }

    @Test
    void utf8LengthCountsBytes() {
        for (String text : List.of("a", "é", "€", "😀", "a,é;€\r\n😀")) {
            assertEquals(text.getBytes(StandardCharsets.UTF_8).length, Csv.utf8Length(text), text);
        }
        assertEquals(3, Csv.utf8Length("\uD83D"), "a lone surrogate: three bytes, never fewer than it may take");
    }

    @Test
    void bothLineEndsAreReadAndTheLastRecordMayHaveNone() throws Csv.Malformed {
        List<Csv.Record> records = Csv.read("a,b\r\n1,2\n3,4", ',');

        assertEquals(List.of(row("a", "b"), row("1", "2"), row("3", "4")), fields(records));
        assertEquals(List.of(1, 2, 3), lines(records));
    }

    @Test
    void anEmptyFieldIsNullAndTwoQuotesAreTheEmptyText() throws Csv.Malformed {
        assertEquals(List.of(row("a", "b", "c"), row(null, "", "x"), row("y", null, null)),
                fields(Csv.read("a,b,c\r\n,\"\",x\r\ny,,\r\n", ',')));
    }

    @Test
    void aByteOrderMarkAtTheStartIsIgnored() throws Csv.Malformed {
        assertEquals(List.of(row("id", "name"), row("1", "x")), fields(Csv.read("\uFEFFid,name\r\n1,x\r\n", ',')));
        assertEquals(List.of(), Csv.read("\uFEFF", ','));
        assertEquals(List.of(), Csv.read("", ','));
    }

    @Test
    void aSemicolonSeparatesAndACommaIsThenText() throws Csv.Malformed {
        assertEquals(List.of(row("a", "b"), row("1;2", "2,50")), fields(Csv.read("a;b\r\n\"1;2\";2,50\r\n", ';')));
    }

    @Test
    void aRecordsLineIsWhereItStartsLineEndsInQuotedFieldsCounted() throws Csv.Malformed {
        List<Csv.Record> records = Csv.read("id,label\r\n1,\"two\r\nlines\"\r\n2,\"x\"\"y\"\r\n3,z", ',');

        assertEquals(List.of(row("id", "label"), row("1", "two\r\nlines"), row("2", "x\"y"), row("3", "z")),
                fields(records));
        assertEquals(List.of(1, 2, 4, 5), lines(records));
    }

    @Test
    void anEmptyLineIsARecordOfOneNullFieldAndEachRecordKeepsItsFieldCount() throws Csv.Malformed {
        assertEquals(List.of(row("a", "b"), row((String) null), row("1", "2")),
                fields(Csv.read("a,b\r\n\r\n1,2\r\n", ',')));
        assertEquals(List.of(3, 2, 4), Csv.read("a,b,c\r\n1,2\r\n1,2,3,4\r\n", ',').stream()
                .map(record -> record.fields().size()).toList());
    }

    @Test
    void aQuoteInsideAnUnquotedFieldAndALoneCarriageReturnAreText() throws Csv.Malformed {
        assertEquals(List.of(row("a"), row("x\"y"), row("p\rq")), fields(Csv.read("a\r\nx\"y\r\np\rq\r\n", ',')));
    }

    @Test
    void aQuoteLeftOpenOrTextAfterAClosingQuoteIsRefusedWithItsLine() {
        assertEquals("line 3: unterminated quoted field", malformed("a\r\n1\r\n\"open\r\nstill"));
        assertEquals("line 2: text after a closing quote", malformed("a\r\n\"x\"y\r\n"));
    }

    @Test
    void whatTheWriterWritesTheReaderReadsBackWithBothSeparators() throws Csv.Malformed {
        List<List<String>> rows = List.of(row("id", "name", "note"), row("1", null, ""),
                row("2", "a,b;c", "say \"hi\"\r\nbye"), row("3", "é😀", "\n"), row((String) null, null, null));
        for (char separator : new char[] {',', ';'}) {
            Csv.Writer out = new Csv.Writer(separator, 10_000);
            for (List<String> row : rows) {
                assertTrue(out.add(row));
            }
            assertEquals(rows, fields(Csv.read(out.text(), separator)), "separator " + separator);
        }
    }
}
