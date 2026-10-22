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

package io.vidocq.bodoni.core.tags.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestHttpServletResponse;
import io.vidocq.bodoni.core.testing.TestPageContext;

import jakarta.servlet.jsp.JspTagException;

import org.junit.jupiter.api.Test;

class UrlTagTest {

    private static String url(TestPageContext pc, String value, String context) throws Exception {
        UrlTag tag = new UrlTag();
        tag.setValue(value);
        tag.setContext(context);
        return TagHarness.run(tag, pc, p -> { });
    }

    @Test
    void contextRelativeIsPrefixedAndRewritten() throws Exception {
        assertEquals("/app/p;jsessionid=T", url(TestPageContext.create(), "/p", null));
    }

    @Test
    void pageRelativeIsRewrittenOnly() throws Exception {
        assertEquals("p;jsessionid=T", url(TestPageContext.create(), "p", null));
    }

    @Test
    void absoluteIsUntouchedAndNeverEncoded() throws Exception {
        TestPageContext pc = TestPageContext.create();
        assertEquals("http://e.org/p", url(pc, "http://e.org/p", null));
        assertTrue(((TestHttpServletResponse) pc.getResponse()).encodedUrls().isEmpty());
    }

    @Test
    void foreignContext() throws Exception {
        assertEquals("/other/p;jsessionid=T", url(TestPageContext.create(), "/p", "/other"));
        assertThrows(JspTagException.class, () -> url(TestPageContext.create(), "/p", "other"));
    }

    @Test
    void parametersAreEncodedWithTheResponseEncoding() throws Exception {
        TestPageContext pc = TestPageContext.create();
        pc.getResponse().setCharacterEncoding("UTF-8");
        UrlTag tag = new UrlTag();
        tag.setValue("/p");
        String out = TagHarness.run(tag, pc, p -> {
            addParam(p, tag, "a", "1");
            addParam(p, tag, "b", "é");
        });
        assertEquals("/app/p?a=1&b=%C3%A9;jsessionid=T", out);
    }

    static void addParam(jakarta.servlet.jsp.PageContext p, jakarta.servlet.jsp.tagext.Tag parent, String name,
            String value) throws Exception {
        ParamTag param = new ParamTag();
        param.setName(name);
        param.setValue(value);
        TagHarness.run(param, (TestPageContext) p, parent, io.vidocq.bodoni.core.testing.Body.EMPTY);
    }

    @Test
    void varExportsInsteadOfWriting() throws Exception {
        TestPageContext pc = TestPageContext.create();
        UrlTag tag = new UrlTag();
        tag.setValue("/p");
        tag.setVar("u");
        tag.setScope("request");
        assertEquals("", TagHarness.run(tag, pc, p -> { }));
        assertEquals("/app/p;jsessionid=T", pc.getRequest().getAttribute("u"));
    }

    @Test
    void reusedInstanceCarriesNoParameter() throws Exception {
        TestPageContext pc = TestPageContext.create();
        UrlTag tag = new UrlTag();
        tag.setValue("/p");
        TagHarness.run(tag, pc, p -> addParam(p, tag, "a", "1"));
        assertEquals("/app/p;jsessionid=T", TagHarness.run(tag, pc, p -> { }));
        TagHarness.run(tag, pc, p -> addParam(p, tag, "a", "1"));
        tag.release();
        tag.setValue("/p");
        assertEquals("/app/p;jsessionid=T", TagHarness.run(tag, pc, p -> { }));
    }
}
