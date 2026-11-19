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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;

import io.vidocq.bodoni.core.testing.Body;
import io.vidocq.bodoni.core.testing.CountingDataSource;
import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestDatabase;
import io.vidocq.bodoni.core.testing.TestHttpServletRequest;
import io.vidocq.bodoni.core.testing.TestHttpServletResponse;
import io.vidocq.bodoni.core.testing.TestPageContext;
import io.vidocq.bodoni.core.testing.TestServletContext;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.PageContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code <sql:update>} (Tags 3.0 §10.4). */
class UpdateTagTest {

    private static final String INSERT = "INSERT INTO book VALUES (4, 'Bodoni')";
    private static final String NEW_BOOK = "SELECT title FROM book WHERE id = 4";

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

    private UpdateTag update(String sql) {
        UpdateTag tag = new UpdateTag();
        tag.setDataSource(ds);
        tag.setSql(sql);
        tag.setVar("n");
        return tag;
    }

    private Object run(UpdateTag tag, Body body) throws Exception {
        TagHarness.run(tag, pc, body);
        return pc.getAttribute("n");
    }

    @Test
    void anInsertCountsOneRow() throws Exception {
        assertEquals(Integer.valueOf(1), run(update(INSERT), Body.NONE));
        assertEquals(List.of(List.of("Bodoni")), db.rows(NEW_BOOK));
    }

    @Test
    void aBoundUpdateCountsTheRowsItChanged() throws Exception {
        UpdateTag tag = update("UPDATE book SET title = UPPER(title) WHERE id > ?");
        assertEquals(2, run(tag, p -> tag.addSQLParameter(1)));
        assertEquals(List.of(List.of("Manuale"), List.of("TIPOGRAFICO"), List.of("PARMA")),
                db.rows("SELECT title FROM book ORDER BY id"));
    }

    @Test
    void theTrimmedBodyIsTheStatement() throws Exception {
        UpdateTag tag = update(null);
        assertEquals(1, run(tag, p -> p.getOut().print("\n   " + INSERT + "\n")));
    }

    /** TCK {@code positiveUpdateNoRowsResultTest}. */
    @Test
    void aDeleteOfNothingCountsZero() throws Exception {
        assertEquals(0, run(update("DELETE FROM book WHERE id = -9999"), Body.NONE));
    }

    @Test
    void aDdlStatementCountsZero() throws Exception {
        assertEquals(0, run(update("CREATE TABLE t (x INT)"), Body.NONE));
        assertEquals(List.of(), db.rows("SELECT x FROM t"));
    }

    @Test
    void withoutVarNothingIsExported() throws Exception {
        UpdateTag tag = update(INSERT);
        tag.setVar(null);
        TagHarness.run(tag, pc, Body.NONE);
        assertFalse(pc.getAttributeNamesInScope(PageContext.PAGE_SCOPE).hasMoreElements());
        assertEquals(List.of(List.of("Bodoni")), db.rows(NEW_BOOK));
    }

    @Test
    void theCountGoesToTheRequestedScope() throws Exception {
        UpdateTag tag = update(INSERT);
        tag.setScope("session");
        TagHarness.run(tag, pc, Body.NONE);
        assertEquals(1, pc.getAttribute("n", PageContext.SESSION_SCOPE));
    }

    /** TCK {@code negativeQueryNoVarAttributeTest}: scope without var. */
    @Test
    void scopeWithoutVarFails() {
        UpdateTag tag = update(INSERT);
        tag.setVar(null);
        tag.setScope("page");
        JspTagException e = assertThrows(JspTagException.class, () -> run(tag, Body.NONE));
        assertEquals("sql:update: scope is set but var is not", e.getMessage());
        assertEquals(0, ds.opened());
    }

    @Test
    void anEmptyVarFails() {
        UpdateTag tag = update(INSERT);
        tag.setVar("");
        JspTagException e = assertThrows(JspTagException.class, () -> run(tag, Body.NONE));
        assertEquals("sql:update: var must not be empty", e.getMessage());
    }

    /** TCK {@code negativeUpdateSQLAttributeTest}. */
    @Test
    void aFailingStatementIsNamedAndKeptAsCause() {
        String bad = "UPDATE nowhere SET nothing = 1";
        JspException e = assertThrows(JspException.class, () -> run(update(bad), Body.NONE));
        assertTrue(e.getMessage().startsWith("sql:update: cannot execute \"" + bad + "\": "), e.getMessage());
        assertInstanceOf(SQLException.class, e.getCause());
        assertEquals(0, ds.connections().get(0).unclosed());
    }

    @Test
    void anUpdateReleasesItsConnection() throws Exception {
        run(update(INSERT), Body.NONE);
        assertEquals(1, ds.opened());
        List<String> log = ds.connections().get(0).log();
        assertEquals("close()", log.get(log.size() - 1));
        assertEquals(0, ds.connections().get(0).unclosed());
    }

    /** Tags 3.0 §10.4: the message of a failure includes the SQL statement. */
    @Test
    void aConnectionFailureNamesTheStatement() {
        UpdateTag tag = update(INSERT);
        tag.setDataSource(new Books.Unconfigured());
        JspException e = assertThrows(JspException.class, () -> run(tag, Body.NONE));
        assertEquals("sql:update: cannot get a connection from " + Books.Unconfigured.class.getName()
                + " to execute \"" + INSERT + "\": no URL configured", e.getMessage());
        assertInstanceOf(SQLException.class, e.getCause());
    }

    /** Tags 3.0 §10.4: the message of a failure includes the SQL statement. */
    @Test
    void aFailingCloseNamesTheStatement() {
        ds.onOpen(c -> c.failOnConnection("close"));
        JspException e = assertThrows(JspException.class, () -> run(update(INSERT), Body.NONE));
        assertEquals("sql:update: cannot close the connection to " + CountingDataSource.class.getName()
                + " after \"" + INSERT + "\": injected close", e.getMessage());
        assertInstanceOf(SQLException.class, e.getCause());
    }

    @Test
    void aNullDataSourceFails() {
        UpdateTag tag = update(INSERT);
        tag.setDataSource(null);
        JspException e = assertThrows(JspException.class, () -> run(tag, Body.NONE));
        assertEquals("sql:update: dataSource is null", e.getMessage());
    }
}
