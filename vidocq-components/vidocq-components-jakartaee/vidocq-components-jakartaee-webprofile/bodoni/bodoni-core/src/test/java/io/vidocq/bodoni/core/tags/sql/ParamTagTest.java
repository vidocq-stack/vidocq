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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import jakarta.servlet.jsp.tagext.Tag;
import jakarta.servlet.jsp.tagext.TagSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code <sql:param>} (Tags 3.0 §10.7). */
class ParamTagTest {

    private static final String BY_TITLE = "SELECT title FROM book WHERE title = ?";

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

    private QueryTag query(String sql) throws Exception {
        QueryTag tag = new QueryTag();
        tag.setDataSource(new CountingDataSource(db.url()));
        tag.setSql(sql);
        tag.setVar("r");
        return tag;
    }

    private List<Object> titles(QueryTag parent, Body body) throws Exception {
        TagHarness.run(parent, pc, body);
        Result result = (Result) pc.getAttribute("r");
        return List.of(result.getRows()).stream().map(row -> row.get("title")).toList();
    }

    private Body params(Tag parent, ParamTag... tags) {
        return p -> {
            for (ParamTag tag : tags) {
                TagHarness.run(tag, pc, parent, Body.NONE);
            }
        };
    }

    private static ParamTag value(Object value) {
        ParamTag tag = new ParamTag();
        tag.setValue(value);
        return tag;
    }

    @Test
    void theValueAttributeIsBound() throws Exception {
        QueryTag parent = query("SELECT title FROM book WHERE id = ?");
        assertEquals(List.of("Tipografico"), titles(parent, params(parent, value(2))));
    }

    /** TCK positiveParamBodyContentQueryTest. */
    @Test
    void theTrimmedBodyIsTheValue() throws Exception {
        QueryTag parent = query(BY_TITLE);
        ParamTag param = new ParamTag();
        Body body = p -> TagHarness.run(param, pc, parent, q -> q.getOut().print("  Parma "));
        assertEquals(List.of("Parma"), titles(parent, body));
    }

    @Test
    void aNullValueIsBoundAsSqlNull() throws Exception {
        try (var c = db.connect(); var s = c.createStatement()) {
            s.execute("ALTER TABLE book ADD published INT");
            s.execute("UPDATE book SET published = 1 WHERE id <> 2");
        }
        QueryTag parent = query("SELECT title FROM book WHERE published IS NULL OR published = ?");
        assertEquals(List.of("Tipografico"), titles(parent, params(parent, value(null))));
    }

    /** TCK positiveParamQueryMultiSQLAttributeTest. */
    @Test
    void parametersBindInOrder() throws Exception {
        QueryTag parent = query("SELECT title FROM book WHERE id = ? AND title = ?");
        assertEquals(List.of("Tipografico"), titles(parent, params(parent, value(2), value("Tipografico"))));
        QueryTag mismatch = query("SELECT title FROM book WHERE id = ? AND title = ?");
        assertEquals(List.of(), titles(mismatch, params(mismatch, value(1), value("Tipografico"))));
    }

    @Test
    void anInjectionStringIsAValueNotSql() throws Exception {
        QueryTag parent = query(BY_TITLE);
        assertEquals(List.of(), titles(parent, params(parent, value("' OR '1'='1"))));
    }

    @Test
    void insideAnUpdateItBindsToo() throws Exception {
        UpdateTag parent = new UpdateTag();
        parent.setDataSource(new CountingDataSource(db.url()));
        parent.setSql("UPDATE book SET title = 'X' WHERE id = ?");
        parent.setVar("n");
        TagHarness.run(parent, pc, p -> TagHarness.run(value(1), pc, parent, Body.NONE));
        assertEquals(1, pc.getAttribute("n"));
    }

    @Test
    void withoutAnExecutionAncestorItFails() {
        ParamTag param = value(1);
        JspTagException e = assertThrows(JspTagException.class,
                () -> TagHarness.run(param, pc, new TagSupport(), Body.NONE));
        assertEquals("<sql:param> must be nested in an action that implements SQLExecutionTag", e.getMessage());
    }

    /** A parent other than the standard actions receives the value unmodified. */
    @Test
    void aCustomParentReceivesTheValueAsIs() throws Exception {
        Recorder parent = new Recorder();
        Object token = new Object();
        TagHarness.run(value(token), pc, parent, Body.NONE);
        assertSame(token, parent.received.get(0));
    }

    @Test
    void anInstanceIsReusableWithoutLeakingItsOldBody() throws Exception {
        Recorder parent = new Recorder();
        ParamTag param = new ParamTag();
        TagHarness.run(param, pc, parent, p -> p.getOut().print(" first "));
        TagHarness.run(param, pc, parent, Body.NONE);
        assertEquals(java.util.Arrays.asList("first", null), parent.received);
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
