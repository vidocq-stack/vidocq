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

package io.vidocq.bodoni.core.tags.fmt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vidocq.bodoni.core.testing.Body;
import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestHttpServletRequest;
import io.vidocq.bodoni.core.testing.TestPageContext;

import jakarta.servlet.jsp.JspTagException;

import org.junit.jupiter.api.Test;

class RequestEncodingTagTest {

    private static final String CHARSET = "jakarta.servlet.jsp.jstl.fmt.request.charset";

    private final TestPageContext pc = TestPageContext.create();

    private TestHttpServletRequest request() {
        return (TestHttpServletRequest) pc.getRequest();
    }

    @Test
    void valueIsAppliedToTheRequest() throws Exception {
        RequestEncodingTag tag = new RequestEncodingTag();
        tag.setValue("UTF-8");
        TagHarness.run(tag, pc, Body.NONE);
        assertEquals("UTF-8", request().getCharacterEncoding());
    }

    @Test
    void noValueKeepsTheEncodingTheRequestAlreadyHas() throws Exception {
        request().setCharacterEncoding("UTF-16");
        pc.getSession().setAttribute(CHARSET, "Shift_JIS");
        TagHarness.run(new RequestEncodingTag(), pc, Body.NONE);
        assertEquals("UTF-16", request().getCharacterEncoding());
    }

    @Test
    void noValueFallsBackToTheSessionCharset() throws Exception {
        pc.getSession().setAttribute(CHARSET, "UTF-16");
        TagHarness.run(new RequestEncodingTag(), pc, Body.NONE);
        assertEquals("UTF-16", request().getCharacterEncoding());
    }

    @Test
    void noValueAndNothingElseUsesLatin1() throws Exception {
        TagHarness.run(new RequestEncodingTag(), pc, Body.NONE);
        assertEquals("ISO-8859-1", request().getCharacterEncoding());
    }

    @Test
    void noValueWithoutASessionUsesLatin1AndCreatesNoSession() throws Exception {
        io.vidocq.bodoni.core.testing.TestServletContext ctx = new io.vidocq.bodoni.core.testing.TestServletContext();
        TestHttpServletRequest bare = new TestHttpServletRequest(ctx).withoutSession();
        TestPageContext page = new TestPageContext(bare,
                new io.vidocq.bodoni.core.testing.TestHttpServletResponse(), ctx);
        TagHarness.run(new RequestEncodingTag(), page, Body.NONE);
        assertEquals("ISO-8859-1", bare.getCharacterEncoding());
        assertEquals(false, bare.hasSession());
    }

    @Test
    void unsupportedNameIsRejected() {
        RequestEncodingTag tag = new RequestEncodingTag();
        tag.setValue("no-such-charset");
        assertThrows(JspTagException.class, () -> TagHarness.run(tag, pc, Body.NONE));
    }

    @Test
    void illegalNameIsRejected() {
        RequestEncodingTag tag = new RequestEncodingTag();
        tag.setValue("bad name!");
        assertThrows(JspTagException.class, () -> TagHarness.run(tag, pc, Body.NONE));
    }

    @Test
    void reusedInstanceDoesNotKeepTheValue() throws Exception {
        RequestEncodingTag tag = new RequestEncodingTag();
        tag.setValue("UTF-8");
        TagHarness.run(tag, pc, Body.NONE);
        tag.release();
        TestPageContext other = TestPageContext.create();
        TagHarness.run(tag, other, Body.NONE);
        assertEquals("ISO-8859-1", ((TestHttpServletRequest) other.getRequest()).getCharacterEncoding());
    }
}
