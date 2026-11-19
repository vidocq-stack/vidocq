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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What a span says of the SQL it times: operation, table, name, and literals kept out of a plain statement's text. */
class SqlStatementsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "select * from books where id = ?          | SELECT | books",
            "SELECT b.title FROM public.books b       | SELECT | public.books",
            "insert into loans (book, member) values (?, ?) | INSERT | loans",
            "UPDATE \"Loans\" SET returned = ?          | UPDATE | Loans",
            "delete from loans where id = 1           | DELETE | loans",
            "  with x as (select 1) select * from x   | WITH   | ''",
            "call refresh_stats()                     | CALL   | ''"})
    void theOperationAndTableComeFromTheSql(String sql, String operation, String table) {
        SqlStatements.Parsed parsed = SqlStatements.parse(sql);

        assertEquals(operation, parsed.operation());
        assertEquals(table, parsed.collection());
    }

    @Test
    void theSpanIsNamedByOperationAndTableElseByTheDatabase() {
        assertEquals("SELECT books", SqlStatements.spanName(SqlStatements.parse("select * from books"), "h2"));
        assertEquals("CALL", SqlStatements.spanName(SqlStatements.parse("call refresh()"), "h2"));
        assertEquals("postgresql", SqlStatements.spanName(SqlStatements.parse(null), "postgresql"));
    }

    @Test
    void literalsOfAPlainStatementBecomePlaceholders() {
        assertEquals("select * from books where title = ? and id = ? and price > ?",
                SqlStatements.sanitize("select * from books where title = 'It''s' and id = 42 and price > 3.5"));
        assertEquals("select col1, t2.x from t2", SqlStatements.sanitize("select col1, t2.x from t2"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "where a = -1 and b = 2.5e-3 and c = 1E10 | where a = ? and b = ? and c = ?",
            "where x-1 > 0                            | where x-? > ?",
            "select $1, t.2col, 1.5.3, 1e5x, 12abc    | select $1, t.2col, 1.5.3, 1e5x, 12abc",
            "where n = 1.5e+ and m = 7.               | where n = 1.5e+ and m = 7.",
            "where s = 'a''b' and t = ''              | where s = ? and t = ?",
            "where s = 'open and n = 3                | where s = 'open and n = ?",
            "where s = 'a'' and n = 3                 | where s = ?' and n = ?"})
    void numbersAndQuotedStringsAreTheOnlyLiterals(String sql, String sanitized) {
        assertEquals(sanitized, SqlStatements.sanitize(sql));
    }

    @Test
    void aLongStringLiteralDoesNotOverflowTheStack() {
        String sql = "insert into notes values ('" + "x".repeat(200_000) + "')";

        assertEquals("insert into notes values (?)", SqlStatements.sanitize(sql));
    }
}
