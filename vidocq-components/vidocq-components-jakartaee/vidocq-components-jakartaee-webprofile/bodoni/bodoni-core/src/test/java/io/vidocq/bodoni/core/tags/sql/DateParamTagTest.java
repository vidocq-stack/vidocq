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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import io.vidocq.bodoni.core.testing.Body;
import io.vidocq.bodoni.core.testing.CountingDataSource;
import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestDatabase;
import io.vidocq.bodoni.core.testing.TestHttpServletRequest;
import io.vidocq.bodoni.core.testing.TestHttpServletResponse;
import io.vidocq.bodoni.core.testing.TestPageContext;
import io.vidocq.bodoni.core.testing.TestServletContext;

import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.jstl.sql.Result;
import jakarta.servlet.jsp.jstl.sql.SQLExecutionTag;
import jakarta.servlet.jsp.tagext.TagSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code <sql:dateParam>} (Tags 3.0 §10.8). */
class DateParamTagTest {

    private final TestServletContext context = new TestServletContext();
    private final TestPageContext pc =
            new TestPageContext(new TestHttpServletRequest(context), new TestHttpServletResponse(), context);
    private final Timestamp moment = Timestamp.valueOf(LocalDateTime.of(2001, 8, 30, 20, 20, 20));
    private TestDatabase db;

    @BeforeEach
    void setUp() {
        db = TestDatabase.create(
                "CREATE TABLE t3(id INT, d DATE, t TIME, ts TIMESTAMP)",
                "INSERT INTO t3 VALUES (1, DATE '2001-08-30', TIME '20:20:20', TIMESTAMP '2001-08-30 20:20:20')");
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private int found(String column, java.util.Date value, String type) throws Exception {
        QueryTag parent = new QueryTag();
        parent.setDataSource(new CountingDataSource(db.url()));
        parent.setSql("SELECT id FROM t3 WHERE " + column + " = ?");
        parent.setVar("r");
        DateParamTag param = new DateParamTag();
        param.setValue(value);
        param.setType(type);
        TagHarness.run(parent, pc, p -> TagHarness.run(param, pc, parent, Body.NONE));
        return ((Result) pc.getAttribute("r")).getRowCount();
    }

    private static Object bound(java.util.Date value, String type) throws Exception {
        Recorder parent = new Recorder();
        DateParamTag param = new DateParamTag();
        param.setValue(value);
        param.setType(type);
        TagHarness.run(param, new TestPageContext(new TestHttpServletRequest(new TestServletContext()),
                new TestHttpServletResponse(), new TestServletContext()), parent, Body.NONE);
        return parent.received.get(0);
    }

    /** TCK positiveDateParamQueryDateTest. */
    @Test
    void aDateTypeMatchesADateColumn() throws Exception {
        assertEquals(1, found("d", moment, "date"));
    }

    @Test
    void aTimeTypeMatchesATimeColumn() throws Exception {
        assertEquals(1, found("t", moment, "time"));
    }

    @Test
    void aTimestampTypeMatchesATimestampColumn() throws Exception {
        assertEquals(1, found("ts", moment, "timestamp"));
    }

    @Test
    void timestampIsTheDefaultType() throws Exception {
        assertEquals(1, found("ts", new java.util.Date(moment.getTime()), null));
    }

    @Test
    void anAlreadyTypedValueIsPassedAsIs() throws Exception {
        Date date = new Date(moment.getTime());
        assertSame(date, bound(date, "date"));
        Time time = new Time(moment.getTime());
        assertSame(time, bound(time, "time"));
        assertSame(moment, bound(moment, null));
    }

    @Test
    void aTimestampIsConvertedToADate() throws Exception {
        Object value = bound(moment, "date");
        assertInstanceOf(Date.class, value);
        assertEquals(moment.getTime(), ((Date) value).getTime());
    }

    @Test
    void aPlainDateBecomesATimestampByDefault() throws Exception {
        assertInstanceOf(Timestamp.class, bound(new java.util.Date(moment.getTime()), null));
    }

    @Test
    void aNullValueIsNull() throws Exception {
        assertNull(bound(null, "date"));
    }

    /** TCK negativeDateParamTypeAttributeTest. */
    @Test
    void anInvalidTypeFails() {
        JspTagException e = assertThrows(JspTagException.class, () -> bound(moment, "invalidType"));
        assertEquals("sql:dateParam: invalid type 'invalidType': expected date, time or timestamp",
                e.getMessage());
    }

    @Test
    void theTypeIsCaseSensitive() {
        assertThrows(JspTagException.class, () -> bound(moment, "DATE"));
    }

    @Test
    void withoutAnExecutionAncestorItFails() {
        DateParamTag param = new DateParamTag();
        param.setValue(moment);
        JspTagException e = assertThrows(JspTagException.class,
                () -> TagHarness.run(param, pc, new TagSupport(), Body.NONE));
        assertEquals("<sql:dateParam> must be nested in an action that implements SQLExecutionTag",
                e.getMessage());
    }

    /** An execution action stand-in. */
    private static final class Recorder extends TagSupport implements SQLExecutionTag {
        private static final long serialVersionUID = 1L;
        private final List<Object> received = new ArrayList<>();

        @Override
        public void addSQLParameter(Object value) {
            received.add(value);
        }
    }
}
