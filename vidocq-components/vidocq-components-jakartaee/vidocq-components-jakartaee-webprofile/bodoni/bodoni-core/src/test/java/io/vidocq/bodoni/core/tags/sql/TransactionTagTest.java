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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
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
import jakarta.servlet.jsp.jstl.sql.Result;
import jakarta.servlet.jsp.tagext.Tag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code <sql:transaction>} (Tags 3.0 §10.5); the logs mirror the TCK's {@code logDS} lifecycle pages. */
class TransactionTagTest {

    private static final String INSERT_4 = "INSERT INTO book VALUES (4, 'Bodoni')";
    private static final String INSERT_5 = "INSERT INTO book VALUES (5, 'Ibarra')";
    private static final String DUPLICATE = "INSERT INTO book VALUES (1, 'Duplicate')";
    private static final String NEW_BOOKS = "SELECT title FROM book WHERE id > 3 ORDER BY id";
    private static final String GET_ISOLATION = "getTransactionIsolation()";
    private static final String GET_AUTO_COMMIT = "getAutoCommit()";
    private static final String AUTO_COMMIT_OFF = "setAutoCommit(false)";
    private static final String AUTO_COMMIT_ON = "setAutoCommit(true)";
    private static final String COMMIT = "commit()";
    private static final String ROLLBACK = "rollback()";
    private static final String CLOSE = "close()";

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

    private TransactionTag transaction(String isolation) {
        TransactionTag tag = new TransactionTag();
        tag.setDataSource(ds);
        tag.setIsolation(isolation);
        return tag;
    }

    private static UpdateTag update(String sql) {
        UpdateTag tag = new UpdateTag();
        tag.setSql(sql);
        return tag;
    }

    /** A body running {@code nested} in order, each as an empty element. */
    private static Body running(Tag parent, Tag... nested) {
        return p -> {
            for (Tag tag : nested) {
                TagHarness.run(tag, (TestPageContext) p, parent, Body.NONE);
            }
        };
    }

    private List<String> log(int connection) {
        return ds.connections().get(connection).log();
    }

    @Test
    void nestedActionsShareTheCommittedConnection() throws Exception {
        TransactionTag tx = transaction(null);
        TagHarness.run(tx, pc, running(tx, update(INSERT_4), update(INSERT_5)));
        assertEquals(List.of(List.of("Bodoni"), List.of("Ibarra")), db.rows(NEW_BOOKS));
        assertEquals(1, ds.opened());
        assertEquals(List.of(GET_ISOLATION, GET_AUTO_COMMIT, AUTO_COMMIT_OFF, COMMIT, AUTO_COMMIT_ON, CLOSE), log(0));
        assertEquals(0, ds.connections().get(0).unclosed());
        assertNull(tx.connection());
    }

    @Test
    void aDifferentIsolationIsSetThenRestored() throws Exception {
        TransactionTag tx = transaction("serializable");
        TagHarness.run(tx, pc, running(tx, update(INSERT_4)));
        assertEquals(List.of(GET_ISOLATION, GET_AUTO_COMMIT, AUTO_COMMIT_OFF,
                "setTransactionIsolation(" + Connection.TRANSACTION_SERIALIZABLE + ")", COMMIT,
                "setTransactionIsolation(" + Connection.TRANSACTION_READ_COMMITTED + ")", AUTO_COMMIT_ON, CLOSE),
                log(0));
    }

    @Test
    void theCurrentIsolationIsLeftAlone() throws Exception {
        TransactionTag tx = transaction("read_committed");
        TagHarness.run(tx, pc, running(tx, update(INSERT_4)));
        assertEquals(List.of(GET_ISOLATION, GET_AUTO_COMMIT, AUTO_COMMIT_OFF, COMMIT, AUTO_COMMIT_ON, CLOSE), log(0));
    }

    /** TCK {@code positiveTxRollbackLifeCycleTest} and {@code positiveTxUpdateRollbackTest}. */
    @Test
    void aNestedFailureRollsBackAndIsRethrown() {
        TransactionTag tx = transaction(null);
        JspException[] nested = new JspException[1];
        Body body = p -> {
            TagHarness.run(update(INSERT_4), (TestPageContext) p, tx, Body.NONE);
            try {
                TagHarness.run(update(DUPLICATE), (TestPageContext) p, tx, Body.NONE);
            } catch (JspException e) {
                nested[0] = e;
                throw e;
            }
        };
        JspException thrown = assertThrows(JspException.class, () -> TagHarness.run(tx, pc, body));
        assertSame(nested[0], thrown);
        assertEquals(List.of(), db.rows(NEW_BOOKS));
        assertEquals(List.of(GET_ISOLATION, GET_AUTO_COMMIT, AUTO_COMMIT_OFF, ROLLBACK, AUTO_COMMIT_ON, CLOSE), log(0));
    }

    @Test
    void aFailingCommitRollsBack() {
        ds.onOpen(c -> c.failOn("commit"));
        TransactionTag tx = transaction(null);
        Body body = running(tx, update(INSERT_4));
        JspException e = assertThrows(JspException.class, () -> TagHarness.run(tx, pc, body));
        assertEquals("sql:transaction: commit failed: injected commit", e.getMessage());
        assertEquals(List.of(GET_ISOLATION, GET_AUTO_COMMIT, AUTO_COMMIT_OFF, COMMIT, ROLLBACK, AUTO_COMMIT_ON, CLOSE),
                log(0));
        assertEquals(List.of(), db.rows(NEW_BOOKS));
    }

    @Test
    void aDataSourceWithoutTransactionsFails() {
        ds.onOpen(c -> c.isolation(Connection.TRANSACTION_NONE));
        TransactionTag tx = transaction(null);
        JspTagException e = assertThrows(JspTagException.class, () -> TagHarness.run(tx, pc, Body.EMPTY));
        assertEquals("sql:transaction: the data source does not support transactions (TRANSACTION_NONE)",
                e.getMessage());
        assertEquals(List.of(GET_ISOLATION, CLOSE), log(0));
    }

    /** TCK {@code negativeTxIsolationLevelAttributeTest}. */
    @Test
    void anInvalidIsolationFailsBeforeConnecting() {
        TransactionTag tx = transaction("bogus");
        JspTagException e = assertThrows(JspTagException.class, () -> TagHarness.run(tx, pc, Body.EMPTY));
        assertEquals("sql:transaction: invalid isolation 'bogus': expected read_committed, read_uncommitted, "
                + "repeatable_read or serializable", e.getMessage());
        assertEquals(0, ds.opened());
    }

    @Test
    void aCleanupFailureIsLoggedAndTheConnectionStillClosed() throws Exception {
        TransactionTag tx = transaction(null);
        TagHarness.run(tx, pc, p -> ds.connections().get(0).failOn("setAutoCommit"));
        List<String> log = log(0);
        assertEquals(List.of(GET_ISOLATION, GET_AUTO_COMMIT, AUTO_COMMIT_OFF, COMMIT, AUTO_COMMIT_ON, CLOSE), log);
        assertEquals(1, context.logged().size());
        assertTrue(context.logged().get(0).contains("injected setAutoCommit"), context.logged().toString());
    }

    /** TCK {@code negativeTxQueryDataSourceAttributeTest}. */
    @Test
    void aNestedQueryMustNotNameADataSource() {
        TransactionTag tx = transaction(null);
        QueryTag query = new QueryTag();
        query.setSql(Books.ALL);
        query.setVar("r");
        query.setDataSource(ds);
        JspTagException e = assertThrows(JspTagException.class, () -> TagHarness.run(tx, pc, running(tx, query)));
        assertEquals("sql:query: dataSource must not be specified inside <sql:transaction>", e.getMessage());
        assertEquals(1, ds.opened());
    }

    /** TCK {@code negativeTxUpdateDataSourceAttributeTest}. */
    @Test
    void aNestedUpdateMustNotNameADataSource() {
        TransactionTag tx = transaction(null);
        UpdateTag update = update(INSERT_4);
        update.setDataSource(ds);
        JspTagException e = assertThrows(JspTagException.class, () -> TagHarness.run(tx, pc, running(tx, update)));
        assertEquals("sql:update: dataSource must not be specified inside <sql:transaction>", e.getMessage());
    }

    @Test
    void aNestedQueryReadsTheUncommittedInsert() throws Exception {
        TransactionTag tx = transaction(null);
        QueryTag query = new QueryTag();
        query.setSql(NEW_BOOKS);
        query.setVar("r");
        TagHarness.run(tx, pc, running(tx, update(INSERT_4), query));
        Result result = (Result) pc.getAttribute("r");
        assertEquals("Bodoni", result.getRows()[0].get("title"));
        assertEquals(1, ds.opened());
    }

    /** TCK {@code negativeTxDataSourceNullAttributeTest}. */
    @Test
    void aNullDataSourceFails() {
        TransactionTag tx = transaction(null);
        tx.setDataSource(null);
        JspException e = assertThrows(JspException.class, () -> TagHarness.run(tx, pc, Body.EMPTY));
        assertEquals("sql:transaction: dataSource is null", e.getMessage());
    }

    @Test
    void aReusedHandlerKeepsNoSavedIsolation() throws Exception {
        TransactionTag tx = transaction("serializable");
        TagHarness.run(tx, pc, Body.EMPTY);
        ds.onOpen(c -> c.isolation(Connection.TRANSACTION_SERIALIZABLE));
        TagHarness.run(tx, pc, Body.EMPTY);
        assertEquals(List.of(GET_ISOLATION, GET_AUTO_COMMIT, AUTO_COMMIT_OFF, COMMIT, AUTO_COMMIT_ON, CLOSE), log(1));
    }

    @Test
    void noConnectionOutsideAnInvocation() {
        assertNull(new TransactionTag().connection());
    }
}
