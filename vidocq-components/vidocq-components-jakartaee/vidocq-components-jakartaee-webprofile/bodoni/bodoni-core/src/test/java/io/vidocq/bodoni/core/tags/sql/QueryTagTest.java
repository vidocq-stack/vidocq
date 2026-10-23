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

package io.vidocq.bodoni.core.tags.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;

import io.vidocq.bodoni.core.testing.Body;
import io.vidocq.bodoni.core.testing.CountingDataSource;
import io.vidocq.bodoni.core.testing.RecordingConnection;
import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestDatabase;
import io.vidocq.bodoni.core.testing.TestHttpServletRequest;
import io.vidocq.bodoni.core.testing.TestHttpServletResponse;
import io.vidocq.bodoni.core.testing.TestPageContext;
import io.vidocq.bodoni.core.testing.TestServletContext;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.PageContext;
import jakarta.servlet.jsp.jstl.core.Config;
import jakarta.servlet.jsp.jstl.sql.Result;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code <sql:query>} (Tags 3.0 §10.3, §10.9.2). */
class QueryTagTest {

    private static final String BY_ID = "SELECT title FROM book WHERE id = ?";
    private static final String BAD_SQL = "This Will Fail on Any RDBMS I Hope!";
    private static final String TITLE = "title";

    private final TestServletContext context = new TestServletContext();
    private final TestPageContext pc =
            new TestPageContext(new TestHttpServletRequest(context), new TestHttpServletResponse(), context);
    private TestDatabase db;
    private CountingDataSource ds;

    @BeforeEach
    void setUp() {
        db = Books.create();
        ds = new CountingDataSource(db.url());
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private QueryTag query(String sql) {
        QueryTag tag = new QueryTag();
        tag.setDataSource(ds);
        tag.setSql(sql);
        tag.setVar("r");
        return tag;
    }

    private Result run(QueryTag tag, Body body) throws Exception {
        TagHarness.run(tag, pc, body);
        return (Result) pc.getAttribute("r");
    }

    private static Body binding(QueryTag tag, String text, Object... parameters) {
        return p -> {
            p.getOut().print(text);
            for (Object parameter : parameters) {
                tag.addSQLParameter(parameter);
            }
        };
    }

    private static List<Object> titles(Result result) {
        return List.of(result.getRows()).stream().map(row -> row.get(TITLE)).toList();
    }

    @Test
    void theSqlAttributeIsExecuted() throws Exception {
        Result result = run(query(Books.ALL), Body.NONE);
        assertEquals(List.of("Manuale", "Tipografico", "Parma"), titles(result));
        assertFalse(result.isLimitedByMaxRows());
    }

    @Test
    void theTrimmedBodyIsTheStatement() throws Exception {
        QueryTag tag = query(null);
        Result result = run(tag, binding(tag, "\n  SELECT title FROM book WHERE id = ?  ", 2));
        assertEquals(List.of("Tipografico"), titles(result));
    }

    @Test
    void withTheSqlAttributeTheBodyOnlyBindsParameters() throws Exception {
        QueryTag tag = query(BY_ID);
        Result result = run(tag, binding(tag, "SELECT 'body text' FROM book", 3));
        assertEquals(List.of("Parma"), titles(result));
    }

    @Test
    void theResultGoesToTheRequestedScope() throws Exception {
        QueryTag tag = query(Books.ALL);
        tag.setScope("request");
        TagHarness.run(tag, pc, Body.NONE);
        assertNull(pc.getAttribute("r"));
        assertEquals(3, ((Result) pc.getAttribute("r", PageContext.REQUEST_SCOPE)).getRowCount());
    }

    @Test
    void maxRowsLimitsTheResult() throws Exception {
        QueryTag tag = query(Books.ALL);
        tag.setMaxRows(2);
        Result result = run(tag, Body.NONE);
        assertEquals(List.of("Manuale", "Tipografico"), titles(result));
        assertTrue(result.isLimitedByMaxRows());
    }

    @Test
    void exactlyMaxRowsRowsIsNotLimited() throws Exception {
        QueryTag tag = query(Books.ALL);
        tag.setMaxRows(3);
        Result result = run(tag, Body.NONE);
        assertEquals(3, result.getRowCount());
        assertFalse(result.isLimitedByMaxRows());
    }

    @Test
    void theMaxRowsSettingAppliesWithoutTheAttribute() throws Exception {
        Config.set(pc, Config.SQL_MAX_ROWS, "1", PageContext.APPLICATION_SCOPE);
        Result result = run(query(Books.ALL), Body.NONE);
        assertEquals(List.of("Manuale"), titles(result));
        assertTrue(result.isLimitedByMaxRows());
    }

    @Test
    void theMaxRowsAttributeOverridesTheSetting() throws Exception {
        Config.set(pc, Config.SQL_MAX_ROWS, 1, PageContext.APPLICATION_SCOPE);
        QueryTag tag = query(Books.ALL);
        tag.setMaxRows(-1);
        assertEquals(3, run(tag, Body.NONE).getRowCount());
    }

    @Test
    void maxRowsBelowMinusOneFails() {
        QueryTag tag = query(Books.ALL);
        tag.setMaxRows(-5);
        JspTagException e = assertThrows(JspTagException.class, () -> run(tag, Body.NONE));
        assertEquals("sql:query: maxRows must be -1 or more, got -5", e.getMessage());
    }

    @Test
    void startRowSkipsRows() throws Exception {
        QueryTag tag = query(Books.ALL);
        tag.setStartRow(1);
        assertEquals(List.of("Tipografico", "Parma"), titles(run(tag, Body.NONE)));
    }

    @Test
    void startRowAndMaxRowsPage() throws Exception {
        QueryTag tag = query(Books.ALL);
        tag.setStartRow(1);
        tag.setMaxRows(1);
        Result result = run(tag, Body.NONE);
        assertEquals(List.of("Tipografico"), titles(result));
        assertTrue(result.isLimitedByMaxRows());
    }

    @Test
    void maxRowsZeroGivesAnEmptyResult() throws Exception {
        QueryTag tag = query(Books.ALL);
        tag.setMaxRows(0);
        assertEquals(0, run(tag, Body.NONE).getRowCount());
    }

    @Test
    void aHugeStartRowGivesAnEmptyResult() throws Exception {
        QueryTag tag = query(Books.ALL);
        tag.setStartRow(Integer.MAX_VALUE);
        tag.setMaxRows(10);
        assertEquals(0, run(tag, Body.NONE).getRowCount());
    }

    @Test
    void aNegativeStartRowFails() {
        QueryTag tag = query(Books.ALL);
        tag.setStartRow(-1);
        JspTagException e = assertThrows(JspTagException.class, () -> run(tag, Body.NONE));
        assertEquals("sql:query: startRow must be 0 or more, got -1", e.getMessage());
    }

    /** TCK {@code positiveQueryEmptyResultTest}. */
    @Test
    void noMatchGivesAnEmptyResult() throws Exception {
        QueryTag tag = query(BY_ID);
        Result result = run(tag, binding(tag, "", -1));
        assertNotNull(result);
        assertEquals(0, result.getRowCount());
        assertEquals(0, result.getRows().length);
    }

    /** TCK {@code negativeQuerySQLAttributeTest}. */
    @Test
    void aFailingStatementIsNamedAndKeptAsCause() {
        JspException e = assertThrows(JspException.class, () -> run(query(BAD_SQL), Body.NONE));
        assertTrue(e.getMessage().startsWith("sql:query: cannot execute \"" + BAD_SQL + "\": "), e.getMessage());
        assertInstanceOf(SQLException.class, e.getCause());
    }

    @Test
    void anEmptyStatementFails() {
        JspTagException e = assertThrows(JspTagException.class, () -> run(query(null), Body.EMPTY));
        assertEquals("sql:query: the SQL statement is empty", e.getMessage());
        assertEquals(0, ds.opened());
    }

    /** TCK {@code negativeQueryDataSourceNullAttributeTest}. */
    @Test
    void aNullDataSourceFails() {
        QueryTag tag = query(Books.ALL);
        tag.setDataSource(null);
        JspException e = assertThrows(JspException.class, () -> run(tag, Body.NONE));
        assertEquals("sql:query: dataSource is null", e.getMessage());
    }

    /** TCK {@code negativeQueryDataSourceAttributeEmptyTest}. */
    @Test
    void anUnconfiguredDataSourceFailsNamingItsClass() {
        QueryTag tag = query(Books.ALL);
        tag.setDataSource(new Books.Unconfigured());
        JspException e = assertThrows(JspException.class, () -> run(tag, Body.NONE));
        assertTrue(e.getMessage().contains(Books.Unconfigured.class.getName()), e.getMessage());
        assertInstanceOf(SQLException.class, e.getCause());
    }

    @Test
    void aDataSourceOfAnotherTypeFails() {
        QueryTag tag = query(Books.ALL);
        tag.setDataSource(1);
        JspException e = assertThrows(JspException.class, () -> run(tag, Body.NONE));
        assertTrue(e.getMessage().contains("java.lang.Integer"), e.getMessage());
    }

    @Test
    void theStringDataSourceIsAJdbcParameterString() throws Exception {
        QueryTag tag = query(Books.ALL);
        tag.setDataSource(db.jdbcParameters());
        assertEquals(3, run(tag, Body.NONE).getRowCount());
    }

    @Test
    void anInvalidScopeFails() {
        QueryTag tag = query(Books.ALL);
        tag.setScope("bogus");
        assertThrows(JspTagException.class, () -> run(tag, Body.NONE));
    }

    /** TCK {@code negativeQueryVarAttributeTest}. */
    @Test
    void anEmptyVarFails() {
        QueryTag tag = query(Books.ALL);
        tag.setVar("");
        JspTagException e = assertThrows(JspTagException.class, () -> run(tag, Body.NONE));
        assertEquals("sql:query: var must not be empty", e.getMessage());
    }

    @Test
    void aMissingVarFails() {
        QueryTag tag = query(Books.ALL);
        tag.setVar(null);
        JspTagException e = assertThrows(JspTagException.class, () -> run(tag, Body.NONE));
        assertEquals("sql:query: var must be set", e.getMessage());
    }

    // ---- resources (Tags 3.0 §10.2: everything opened is closed) ------------------------------

    private void assertReleased() {
        assertEquals(1, ds.opened());
        RecordingConnection connection = ds.connections().get(0);
        List<String> log = connection.log();
        assertEquals("close()", log.get(log.size() - 1), log.toString());
        assertEquals(0, connection.unclosed());
    }

    @Test
    void aSuccessfulQueryReleasesEverything() throws Exception {
        run(query(Books.ALL), Body.NONE);
        assertReleased();
    }

    @Test
    void aFailingStatementReleasesEverything() {
        assertThrows(JspException.class, () -> run(query(BAD_SQL), Body.NONE));
        assertReleased();
    }

    @Test
    void aFailingBindReleasesEverything() {
        QueryTag tag = query(BY_ID);
        Body body = binding(tag, "", new Object());
        assertThrows(JspException.class, () -> run(tag, body));
        assertReleased();
    }

    @Test
    void aFailingReadReleasesEverything() {
        ds.onOpen(c -> c.failOn("getObject"));
        JspException e = assertThrows(JspException.class, () -> run(query(Books.ALL), Body.NONE));
        assertEquals("injected getObject", e.getCause().getMessage());
        assertReleased();
    }

    // ---- reuse: per-invocation state is reset ---------------------------------------------------

    @Test
    void aReusedHandlerBindsOnlyTheParametersOfEachRun() throws Exception {
        QueryTag tag = query(BY_ID);
        assertEquals(List.of("Manuale"), titles(run(tag, binding(tag, "", 1))));
        assertEquals(List.of("Parma"), titles(run(tag, binding(tag, "", 3))));
    }

    @Test
    void aReusedHandlerDoesNotKeepTheBodyOfThePreviousRun() throws Exception {
        QueryTag tag = query(null);
        assertEquals(1, run(tag, binding(tag, "SELECT title FROM book WHERE id = 1")).getRowCount());
        JspTagException e = assertThrows(JspTagException.class, () -> run(tag, Body.NONE));
        assertEquals("sql:query: the SQL statement is empty", e.getMessage());
    }

    @Test
    void aReusedHandlerForgetsTheParametersOfThePreviousRun() throws Exception {
        QueryTag tag = query("SELECT title FROM book WHERE id = ? OR id = ?");
        assertEquals(2, run(tag, binding(tag, "", 1, 2)).getRowCount());
        tag.setSql("SELECT COUNT(*) AS n FROM book");
        Result result = run(tag, Body.EMPTY);
        assertEquals(3L, ((Number) result.getRows()[0].get("n")).longValue());
    }

    @Test
    void releaseClearsTheAttributes() {
        QueryTag tag = query(Books.ALL);
        tag.release();
        assertThrows(JspTagException.class, () -> run(tag, Body.NONE));
    }
}
