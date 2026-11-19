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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import javax.sql.DataSource;

import io.vidocq.bodoni.core.testing.CountingDataSource;
import io.vidocq.bodoni.core.testing.TestDatabase;
import io.vidocq.bodoni.core.testing.TestHttpServletRequest;
import io.vidocq.bodoni.core.testing.TestHttpServletResponse;
import io.vidocq.bodoni.core.testing.TestPageContext;
import io.vidocq.bodoni.core.testing.TestServletContext;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.PageContext;
import jakarta.servlet.jsp.jstl.core.Config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Data-source and setting resolution shared by the {@code sql:} actions (Tags 3.0 §10.2, §10.9, §2.8). */
class SqlSupportTest {

    private static final String ACTION = "sql:query";

    private final TestServletContext context = new TestServletContext();
    private final TestPageContext pc =
            new TestPageContext(new TestHttpServletRequest(context), new TestHttpServletResponse(), context);
    private TestDatabase db;

    @BeforeEach
    void setUp() {
        db = Books.create();
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void aSpecifiedNullDataSourceFails() {
        JspException e = assertThrows(JspException.class, () -> SqlSupport.open(ACTION, true, null, pc, null));
        assertEquals("sql:query: dataSource is null", e.getMessage());
    }

    @Test
    void noAttributeAndNoSettingFails() {
        JspException e = assertThrows(JspException.class, () -> SqlSupport.open(ACTION, false, null, pc, null));
        assertEquals("sql:query: no data source: neither the dataSource attribute nor "
                + "jakarta.servlet.jsp.jstl.sql.dataSource is set", e.getMessage());
    }

    @Test
    void theSettingIsUsedWithoutAnAttribute() throws JspException, SQLException {
        CountingDataSource ds = new CountingDataSource(db.url());
        Config.set(pc, Config.SQL_DATA_SOURCE, ds, PageContext.REQUEST_SCOPE);
        SqlSupport.Opened opened = SqlSupport.open(ACTION, false, null, pc, null);
        try (Connection c = opened.connection()) {
            assertSame(ds.connections().get(0).proxy(), c);
        }
        assertEquals(CountingDataSource.class.getName(), opened.source());
    }

    @Test
    void theContextParameterIsAJdbcParameterString() throws JspException, SQLException {
        context.initParameter(Config.SQL_DATA_SOURCE, db.jdbcParameters());
        SqlSupport.Opened opened = SqlSupport.open(ACTION, false, null, pc, null);
        try (Connection c = opened.connection()) {
            assertFalse(c.isClosed());
        }
        assertTrue(opened.source().startsWith("jdbc:h2:mem:bodoni-"), opened.source());
    }

    /** TCK {@code positiveQueryDataSourceConfigPrecedenceTest}. */
    @Test
    void theAttributeWinsOverTheSetting() throws JspException, SQLException {
        CountingDataSource attribute = new CountingDataSource(db.url());
        Config.set(pc, Config.SQL_DATA_SOURCE, new Books.Unconfigured(), PageContext.REQUEST_SCOPE);
        try (Connection c = SqlSupport.open(ACTION, true, attribute, pc, null).connection()) {
            assertSame(attribute.connections().get(0).proxy(), c);
        }
    }

    @Test
    void aResolutionFailureNamesTheAction() {
        JspException e = assertThrows(JspException.class, () -> SqlSupport.open(ACTION, true, 1, pc, null));
        assertTrue(e.getMessage().startsWith("sql:query: "), e.getMessage());
        assertTrue(e.getMessage().contains("java.lang.Integer"), e.getMessage());
        assertInstanceOf(SQLException.class, e.getCause());
    }

    @Test
    void aConnectionFailureDescribesTheSourceWithoutItsPassword() {
        String secret = "jdbc:none:x;PASSWORD=hunter2,,sa,hunter2";
        DataSource ds = new Books.Unconfigured();
        JspException e = assertThrows(JspException.class, () -> SqlSupport.connect(ACTION, ds, secret, null));
        assertTrue(e.getMessage().startsWith("sql:query: cannot get a connection from "), e.getMessage());
        assertTrue(e.getMessage().endsWith(": no URL configured"), e.getMessage());
        assertFalse(e.getMessage().contains("hunter2"), e.getMessage());
        assertInstanceOf(SQLException.class, e.getCause());
    }

    @Test
    void maxRowsIsUnlimitedWithoutASetting() throws JspTagException {
        assertEquals(-1, SqlSupport.maxRowsSetting(pc));
    }

    @Test
    void maxRowsSettingAcceptsAnInteger() throws JspTagException {
        Config.set(pc, Config.SQL_MAX_ROWS, 5, PageContext.PAGE_SCOPE);
        assertEquals(5, SqlSupport.maxRowsSetting(pc));
    }

    @Test
    void maxRowsSettingAcceptsAString() throws JspTagException {
        context.initParameter(Config.SQL_MAX_ROWS, "20");
        assertEquals(20, SqlSupport.maxRowsSetting(pc));
    }

    @Test
    void aBlankMaxRowsSettingIsUnlimited() throws JspTagException {
        context.initParameter(Config.SQL_MAX_ROWS, " ");
        assertEquals(-1, SqlSupport.maxRowsSetting(pc));
    }

    /** TCK {@code negativeQueryMaxRowsConfigTest} and {@code negativeQueryMaxRowsConfigTest2}. */
    @ParameterizedTest
    @ValueSource(strings = {"invalid", "-5"})
    void anInvalidMaxRowsSettingFails(String value) {
        context.initParameter(Config.SQL_MAX_ROWS, value);
        JspTagException e = assertThrows(JspTagException.class, () -> SqlSupport.maxRowsSetting(pc));
        assertEquals("sql:query: jakarta.servlet.jsp.jstl.sql.maxRows must be an integer of -1 or more, got '"
                + value + "'", e.getMessage());
    }

    static Stream<Number> integralMaxRows() {
        return Stream.of((short) 7, (byte) 7, 7L);
    }

    @ParameterizedTest
    @MethodSource("integralMaxRows")
    void maxRowsSettingAcceptsAnIntegralNumberInIntRange(Number value) throws JspTagException {
        Config.set(pc, Config.SQL_MAX_ROWS, value, PageContext.PAGE_SCOPE);
        assertEquals(7, SqlSupport.maxRowsSetting(pc));
    }

    static Stream<Number> nonIntegerMaxRows() {
        return Stream.of(4294967296L, (long) Integer.MIN_VALUE - 1, 2.5d, 2.0f, new BigDecimal("3"),
                BigInteger.TWO);
    }

    @ParameterizedTest
    @MethodSource("nonIntegerMaxRows")
    void aMaxRowsSettingThatIsNoIntFails(Number value) {
        Config.set(pc, Config.SQL_MAX_ROWS, value, PageContext.PAGE_SCOPE);
        JspTagException e = assertThrows(JspTagException.class, () -> SqlSupport.maxRowsSetting(pc));
        assertEquals("sql:query: jakarta.servlet.jsp.jstl.sql.maxRows must be an integer of -1 or more, got '"
                + value + "'", e.getMessage());
    }

    @Test
    void aMaxRowsSettingOfAnotherTypeFails() {
        Config.set(pc, Config.SQL_MAX_ROWS, List.of(1), PageContext.PAGE_SCOPE);
        assertThrows(JspTagException.class, () -> SqlSupport.maxRowsSetting(pc));
    }

    @Test
    void aNullParameterIsBoundAsSqlNull() throws SQLException {
        try (Connection c = db.connect();
                PreparedStatement s = c.prepareStatement("SELECT id FROM book WHERE title = ?")) {
            SqlSupport.bind(s, Arrays.asList((Object) null));
            try (ResultSet rs = s.executeQuery()) {
                assertFalse(rs.next());
            }
        }
    }

    @Test
    void parametersAreBoundInOrder() throws SQLException {
        try (Connection c = db.connect();
                PreparedStatement s = c.prepareStatement("SELECT title FROM book WHERE id = ? OR id = ? ORDER BY id")) {
            SqlSupport.bind(s, List.of(3, 1));
            try (ResultSet rs = s.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("Manuale", rs.getString(1));
                assertTrue(rs.next());
                assertEquals("Parma", rs.getString(1));
            }
        }
    }
}
