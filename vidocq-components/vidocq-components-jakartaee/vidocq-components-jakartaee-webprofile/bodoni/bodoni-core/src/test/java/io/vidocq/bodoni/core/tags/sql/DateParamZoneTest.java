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

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import io.vidocq.bodoni.core.testing.Body;
import io.vidocq.bodoni.core.testing.CountingDataSource;
import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestDatabase;
import io.vidocq.bodoni.core.testing.TestHttpServletRequest;
import io.vidocq.bodoni.core.testing.TestHttpServletResponse;
import io.vidocq.bodoni.core.testing.TestPageContext;
import io.vidocq.bodoni.core.testing.TestServletContext;

import jakarta.servlet.jsp.jstl.sql.Result;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * {@code <sql:dateParam>} values before 1891 in Europe/Paris (Tags 3.0 §10.8, BUG.md BOD-005).
 *
 * <p>The legacy {@code java.util} zone data gives Paris CET (+1:00) before 1891 while
 * {@code java.time} gives local mean time (+0:09:21); a driver converting a {@code java.sql.Date}
 * with {@code java.time} rules (H2) would bind the day before. The test switches the JVM default
 * zone, so it runs isolated; the H2 session zone is set to the same zone, since H2 may have cached
 * the default zone of an earlier test.
 */
@Isolated
class DateParamZoneTest {

    private static final String PARIS = "Europe/Paris";

    private final TestServletContext context = new TestServletContext();
    private final TestPageContext pc =
            new TestPageContext(new TestHttpServletRequest(context), new TestHttpServletResponse(), context);
    private TimeZone saved;
    private TestDatabase db;

    @BeforeEach
    void setUp() {
        saved = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(PARIS));
        db = TestDatabase.create(
                "CREATE TABLE t4(id INT, d DATE, t TIME, ts TIMESTAMP)",
                "INSERT INTO t4 VALUES (1, DATE '1788-01-01', TIME '12:00:00', TIMESTAMP '1788-01-01 12:00:00')");
    }

    @AfterEach
    void tearDown() {
        try {
            db.close();
        } finally {
            TimeZone.setDefault(saved);
        }
    }

    /** 1788-01-01 at {@code hour}:00 in the default zone, as fmt:parseDate builds it. */
    private static java.util.Date paris1788(int hour) {
        return new GregorianCalendar(1788, Calendar.JANUARY, 1, hour, 0).getTime();
    }

    private int found(String column, java.util.Date value, String type) throws Exception {
        QueryTag parent = new QueryTag();
        parent.setDataSource(new CountingDataSource(db.url() + ";TIME ZONE=" + PARIS));
        parent.setSql("SELECT id FROM t4 WHERE " + column + " = ?");
        parent.setVar("r");
        DateParamTag param = new DateParamTag();
        param.setValue(value);
        param.setType(type);
        TagHarness.run(parent, pc, p -> TagHarness.run(param, pc, parent, Body.NONE));
        return ((Result) pc.getAttribute("r")).getRowCount();
    }

    @Test
    void aDateBefore1891FindsItsDay() throws Exception {
        assertEquals(1, found("d", paris1788(0), "date"));
    }

    @Test
    void aTimestampBefore1891FindsItsExactTime() throws Exception {
        assertEquals(1, found("ts", paris1788(12), "timestamp"));
    }

    @Test
    void aTimeTakenFromADateBefore1891KeepsItsWallClockTime() throws Exception {
        assertEquals(1, found("t", paris1788(12), "time"));
    }
}
