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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import io.vidocq.bodoni.core.internal.DriverManagerDataSource;
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
import jakarta.servlet.jsp.jstl.core.Config;
import jakarta.servlet.jsp.jstl.sql.Result;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code <sql:setDataSource>} (Tags 3.0 §10.6). */
class SetDataSourceTagTest {

    private static final String DS = "ds";
    private static final String H2_DRIVER = "org.h2.Driver";
    private static final String PREFIX = "sql:setDataSource: ";

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

    private SetDataSourceTag urlTag() {
        SetDataSourceTag tag = new SetDataSourceTag();
        tag.setUrl(db.url());
        tag.setDriver(H2_DRIVER);
        tag.setUser("sa");
        tag.setPassword("");
        return tag;
    }

    private void run(SetDataSourceTag tag) throws Exception {
        TagHarness.run(tag, pc, Body.NONE);
    }

    @Test
    void urlDriverUserAndPasswordMakeADataSource() throws Exception {
        SetDataSourceTag tag = urlTag();
        tag.setVar(DS);
        run(tag);
        DataSource ds = assertInstanceOf(DataSource.class, pc.getAttribute(DS));
        try (Connection c = ds.getConnection()) {
            assertTrue(c.isValid(1));
        }
    }

    @Test
    void anEmptyDriverMeansNoDriverClass() throws Exception {
        SetDataSourceTag tag = urlTag();
        tag.setDriver("");
        tag.setVar(DS);
        run(tag);
        assertNotNull(pc.getAttribute(DS));
    }

    /** TCK positiveSetDataSourceQueryNoVarAttributeDataSourceTest. */
    @Test
    void withoutVarTheSettingIsConfiguredAndUsedByAQuery() throws Exception {
        SetDataSourceTag tag = urlTag();
        tag.setScope("request");
        run(tag);
        assertInstanceOf(DataSource.class, Config.get(pc, Config.SQL_DATA_SOURCE, PageContext.REQUEST_SCOPE));
        QueryTag query = new QueryTag();
        query.setSql(Books.ALL);
        query.setVar("r");
        TagHarness.run(query, pc, Body.NONE);
        assertEquals(3, ((Result) pc.getAttribute("r")).getRowCount());
    }

    /** TCK positiveSetDataSourceScopeNoVarAttributeTest. */
    @Test
    void aDataSourceObjectIsExportedAsIsInEachScope() throws Exception {
        CountingDataSource source = new CountingDataSource(db.url());
        String[] names = {"page", "request", "session", "application"};
        int[] scopes = {PageContext.PAGE_SCOPE, PageContext.REQUEST_SCOPE, PageContext.SESSION_SCOPE,
            PageContext.APPLICATION_SCOPE};
        for (int i = 0; i < names.length; i++) {
            SetDataSourceTag tag = new SetDataSourceTag();
            tag.setDataSource(source);
            tag.setScope(names[i]);
            run(tag);
            assertSame(source, Config.get(pc, Config.SQL_DATA_SOURCE, scopes[i]));
        }
        assertEquals(0, source.opened());
    }

    @Test
    void aParameterStringIsResolvedToADriverManagerDataSource() throws Exception {
        SetDataSourceTag tag = new SetDataSourceTag();
        tag.setDataSource(db.jdbcParameters());
        tag.setVar(DS);
        run(tag);
        assertInstanceOf(DriverManagerDataSource.class, pc.getAttribute(DS));
    }

    @Test
    void theDataSourceAttributeWinsOverTheUrl() throws Exception {
        CountingDataSource source = new CountingDataSource(db.url());
        SetDataSourceTag tag = urlTag();
        tag.setDataSource(source);
        tag.setVar(DS);
        run(tag);
        assertSame(source, pc.getAttribute(DS));
    }

    /** TCK negativeSetDataSourceDataSourceAttributeDriverManagerTest. */
    @Test
    void anInvalidParameterStringFailsAtSetDataSource() {
        SetDataSourceTag tag = new SetDataSourceTag();
        tag.setDataSource("invalid,invalid,invalid,invalid");
        tag.setVar(DS);
        JspException e = assertThrows(JspException.class, () -> run(tag));
        assertTrue(e.getMessage().startsWith(PREFIX), e.getMessage());
        assertInstanceOf(SQLException.class, e.getCause());
        assertNull(pc.getAttribute(DS));
    }

    @Test
    void aNullDataSourceFails() {
        SetDataSourceTag tag = new SetDataSourceTag();
        tag.setDataSource(null);
        JspException e = assertThrows(JspException.class, () -> run(tag));
        assertEquals(PREFIX + "dataSource is null", e.getMessage());
    }

    @Test
    void neitherDataSourceNorUrlFails() {
        SetDataSourceTag tag = new SetDataSourceTag();
        tag.setVar(DS);
        JspException e = assertThrows(JspException.class, () -> run(tag));
        assertEquals(PREFIX + "no data source: set dataSource or url", e.getMessage());
    }

    @Test
    void anInvalidScopeFails() {
        SetDataSourceTag tag = urlTag();
        tag.setVar(DS);
        tag.setScope("invalid");
        JspTagException e = assertThrows(JspTagException.class, () -> run(tag));
        assertTrue(e.getMessage().startsWith(PREFIX), e.getMessage());
    }

    /** Review focus 1: the password never reaches a string or a message. */
    @Test
    void thePasswordStaysOutOfStringsAndMessages() throws Exception {
        SetDataSourceTag tag = new SetDataSourceTag();
        tag.setUrl("jdbc:bodoni-none:x");
        tag.setPassword("s3cret");
        tag.setVar(DS);
        run(tag);
        Object ds = pc.getAttribute(DS);
        assertFalse(String.valueOf(ds).contains("s3cret"));
        QueryTag query = new QueryTag();
        query.setDataSource(ds);
        query.setSql("SELECT 1");
        JspException e = assertThrows(JspException.class, () -> TagHarness.run(query, pc, Body.NONE));
        assertFalse(e.getMessage().contains("s3cret"), e.getMessage());
    }

    @Test
    void aReusedInstanceExportsAgainToTheSameVariable() throws Exception {
        SetDataSourceTag tag = urlTag();
        tag.setVar(DS);
        run(tag);
        Object first = pc.getAttribute(DS);
        pc.removeAttribute(DS);
        run(tag);
        assertNotNull(pc.getAttribute(DS));
        assertNotSame(first, pc.getAttribute(DS));
    }
}
