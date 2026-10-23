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

package io.vidocq.bodoni.core.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import io.vidocq.bodoni.core.testing.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QueryResultTest {

    private static final String ALL = "SELECT id, title, published FROM book ORDER BY id";

    private TestDatabase db;

    @BeforeEach
    void setUp() {
        db = TestDatabase.create(
                "CREATE TABLE book(id INT PRIMARY KEY, title VARCHAR(40), published DATE)",
                "INSERT INTO book VALUES (1, 'Manuale', '1788-01-01')",
                "INSERT INTO book VALUES (2, 'Tipografico', NULL)",
                "INSERT INTO book VALUES (3, 'Parma', '1818-01-01')");
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private QueryResult run(String sql, int startRow, int maxRows) throws SQLException {
        try (Connection c = db.connect();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            return QueryResult.of(rs, startRow, maxRows);
        }
    }

    private QueryResult all() throws SQLException {
        return run(ALL, 0, -1);
    }

    @Test
    void columnNamesAreTheLabels() throws SQLException {
        assertArrayEquals(new String[] {"ID", "TITLE", "PUBLISHED"}, all().getColumnNames());
        assertArrayEquals(new String[] {"num"},
                run("SELECT id AS \"num\" FROM book", 0, -1).getColumnNames());
    }

    @Test
    void duplicatedLabelKeepsOneEntryButTwoColumns() throws SQLException {
        QueryResult r = run("SELECT id, id FROM book ORDER BY id", 0, -1);
        assertEquals(1, r.getRows()[0].size());
        assertEquals(2, r.getRowsByIndex()[0].length);
    }

    @Test
    void rowMapsAreCaseInsensitive() throws SQLException {
        var rows = all().getRows();
        assertEquals("Tipografico", rows[1].get("title"));
        assertEquals("Tipografico", rows[1].get("TITLE"));
        assertEquals("Tipografico", rows[1].get("Title"));
        assertNull(rows[1].get("published"));
        assertTrue(rows[1].containsKey("published"));
        assertInstanceOf(Date.class, rows[0].get("published"));
        assertInstanceOf(Integer.class, rows[0].get("id"));
    }

    @Test
    void rowsByIndexHoldTheValues() throws SQLException {
        QueryResult r = all();
        Object[] third = r.getRowsByIndex()[2];
        assertEquals(3, third.length);
        assertEquals(3, third[0]);
        assertEquals("Parma", third[1]);
        assertEquals("1818-01-01", third[2].toString());
        assertEquals(3, r.getRowCount());
        assertFalse(r.isLimitedByMaxRows());
    }

    @Test
    void startRowSkipsRows() throws SQLException {
        QueryResult r = run(ALL, 1, -1);
        assertEquals(2, r.getRowCount());
        assertEquals("Tipografico", r.getRows()[0].get("title"));
        QueryResult beyond = run(ALL, 5, -1);
        assertEquals(0, beyond.getRows().length);
        assertFalse(beyond.isLimitedByMaxRows());
    }

    @Test
    void maxRowsLimits() throws SQLException {
        QueryResult two = run(ALL, 0, 2);
        assertEquals(2, two.getRowCount());
        assertTrue(two.isLimitedByMaxRows());

        QueryResult exact = run(ALL, 0, 3);
        assertEquals(3, exact.getRowCount());
        assertFalse(exact.isLimitedByMaxRows());

        QueryResult zero = run(ALL, 0, 0);
        assertEquals(0, zero.getRowCount());
        assertTrue(zero.isLimitedByMaxRows());

        QueryResult window = run(ALL, 1, 1);
        assertEquals(1, window.getRowCount());
        assertEquals("Tipografico", window.getRows()[0].get("title"));
        assertTrue(window.isLimitedByMaxRows());
    }

    @Test
    void maxRowsZeroOnEmptyTableIsNotLimited() throws SQLException {
        assertFalse(run("SELECT id, title, published FROM book WHERE id > 99", 0, 0).isLimitedByMaxRows());
    }

    @Test
    void gettersReturnCopies() throws SQLException {
        QueryResult r = all();
        r.getRows()[0] = null;
        assertEquals(1, r.getRows()[0].get("id"));
        r.getColumnNames()[0] = "x";
        assertEquals("ID", r.getColumnNames()[0]);
        r.getRowsByIndex()[0][0] = null;
        assertEquals(1, r.getRowsByIndex()[0][0]);
        assertThrows(UnsupportedOperationException.class, () -> r.getRows()[0].put("x", 1));
    }

    @Test
    void emptyTableKeepsColumnNames() throws SQLException {
        QueryResult r = run("SELECT id, title, published FROM book WHERE id > 99", 0, -1);
        assertEquals(0, r.getRows().length);
        assertArrayEquals(new String[] {"ID", "TITLE", "PUBLISHED"}, r.getColumnNames());
    }
}
