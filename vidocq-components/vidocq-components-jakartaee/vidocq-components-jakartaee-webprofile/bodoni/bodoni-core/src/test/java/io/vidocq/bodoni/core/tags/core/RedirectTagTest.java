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

import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestHttpServletResponse;
import io.vidocq.bodoni.core.testing.TestPageContext;

import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.tagext.Tag;

import org.junit.jupiter.api.Test;

class RedirectTagTest {

    @Test
    void redirectsToTheEncodedResolvedUrlAndSkipsThePage() throws Exception {
        TestPageContext pc = TestPageContext.create();
        RedirectTag tag = new RedirectTag();
        tag.setUrl("/next");
        TagHarness.Result r = TagHarness.runForResult(tag, pc, null, p -> { });
        assertEquals(Tag.SKIP_PAGE, r.endTagResult());
        TestHttpServletResponse response = (TestHttpServletResponse) pc.getResponse();
        assertEquals("/app/next;jsessionid=T", response.redirect());
        assertEquals(java.util.List.of("/app/next"), response.encodedRedirectUrls());
    }

    @Test
    void parametersAndForeignContext() throws Exception {
        TestPageContext pc = TestPageContext.create();
        pc.getResponse().setCharacterEncoding("UTF-8");
        RedirectTag tag = new RedirectTag();
        tag.setUrl("/next");
        tag.setContext("/other");
        TagHarness.run(tag, pc, p -> UrlTagTest.addParam(p, tag, "q", "é"));
        assertEquals("/other/next?q=%C3%A9;jsessionid=T", ((TestHttpServletResponse) pc.getResponse()).redirect());
    }

    @Test
    void absoluteIsNotEncoded() throws Exception {
        TestPageContext pc = TestPageContext.create();
        RedirectTag tag = new RedirectTag();
        tag.setUrl("https://e.org/x");
        TagHarness.run(tag, pc, p -> { });
        TestHttpServletResponse response = (TestHttpServletResponse) pc.getResponse();
        assertEquals("https://e.org/x", response.redirect());
        assertEquals(java.util.List.of(), response.encodedRedirectUrls());
    }

    @Test
    void nullUrlIsAnError() {
        assertThrows(JspTagException.class, () -> TagHarness.run(new RedirectTag(), TestPageContext.create(), p -> { }));
    }

    @Test
    void reusedInstanceCarriesNoParameter() throws Exception {
        TestPageContext pc = TestPageContext.create();
        RedirectTag tag = new RedirectTag();
        tag.setUrl("/n");
        TagHarness.run(tag, pc, p -> UrlTagTest.addParam(p, tag, "a", "1"));
        TagHarness.run(tag, pc, p -> { });
        assertEquals("/app/n;jsessionid=T", ((TestHttpServletResponse) pc.getResponse()).redirect());
    }
}
