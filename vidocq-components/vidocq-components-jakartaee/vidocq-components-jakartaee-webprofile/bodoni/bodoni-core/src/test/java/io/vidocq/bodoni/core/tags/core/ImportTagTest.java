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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import io.vidocq.bodoni.core.internal.ExternalResources;
import io.vidocq.bodoni.core.testing.Body;
import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestHttpServletRequest;
import io.vidocq.bodoni.core.testing.TestPageContext;
import io.vidocq.bodoni.core.testing.TestServletContext;

import jakarta.servlet.jsp.JspTagException;

import org.junit.jupiter.api.Test;

class ImportTagTest {

    private static TestHttpServletRequest req(TestPageContext pc) {
        return (TestHttpServletRequest) pc.getRequest();
    }

    @Test
    void relativeImportWritesTheDispatchedText() throws Exception {
        TestPageContext pc = TestPageContext.create();
        req(pc).dispatchers().content("/inc", "hello");
        ImportTag tag = new ImportTag();
        tag.setUrl("/inc");
        assertEquals("hello", TagHarness.run(tag, pc, Body.EMPTY));
    }

    @Test
    void parametersAreAddedToTheDispatchedPath() throws Exception {
        TestPageContext pc = TestPageContext.create();
        pc.getResponse().setCharacterEncoding("UTF-8");
        req(pc).dispatchers().content("/inc?a=1&b=%C3%A9", "x");
        ImportTag tag = new ImportTag();
        tag.setUrl("/inc");
        tag.setVar("v");
        TagHarness.run(tag, pc, p -> {
            UrlTagTest.addParam(p, tag, "a", "1");
            UrlTagTest.addParam(p, tag, "b", "é");
        });
        assertEquals("x", pc.getAttribute("v"));
        assertEquals(List.of("/inc?a=1&b=%C3%A9"), req(pc).dispatchers().calls());
    }

    @Test
    void varExportsAString() throws Exception {
        TestPageContext pc = TestPageContext.create();
        req(pc).dispatchers().content("/inc", "hello");
        ImportTag tag = new ImportTag();
        tag.setUrl("/inc");
        tag.setVar("v");
        tag.setScope("session");
        assertEquals("", TagHarness.run(tag, pc, Body.EMPTY));
        assertEquals("hello", pc.getSession().getAttribute("v"));
    }

    @Test
    void varReaderIsNestedAndClosed() throws Exception {
        TestPageContext pc = TestPageContext.create();
        req(pc).dispatchers().content("/inc", "abc");
        ImportTag tag = new ImportTag();
        tag.setUrl("/inc");
        tag.setVarReader("r");
        List<Object> seen = new ArrayList<>();
        String out = TagHarness.run(tag, pc, p -> {
            Reader r = (Reader) p.getAttribute("r");
            seen.add(r);
            char[] buf = new char[10];
            int n = r.read(buf);
            p.getOut().print(new String(buf, 0, n));
        });
        assertEquals("abc", out);
        assertNull(pc.getAttribute("r"));
        assertInstanceOf(Reader.class, seen.get(0));
        assertThrows(IOException.class, () -> ((Reader) seen.get(0)).read());
    }

    @Test
    void paramIsIllegalWithVarReader() {
        TestPageContext pc = TestPageContext.create();
        req(pc).dispatchers().content("/inc", "abc");
        ImportTag tag = new ImportTag();
        tag.setUrl("/inc");
        tag.setVarReader("r");
        assertThrows(JspTagException.class,
                () -> TagHarness.run(tag, pc, p -> UrlTagTest.addParam(p, tag, "a", "1")));
        assertNull(pc.getAttribute("r"));
    }

    @Test
    void errorStatusIsAnError() {
        TestPageContext pc = TestPageContext.create();
        req(pc).dispatchers().status("/gone", 404);
        ImportTag tag = new ImportTag();
        tag.setUrl("/gone");
        JspTagException e = assertThrows(JspTagException.class, () -> TagHarness.run(tag, pc, Body.EMPTY));
        assertTrue(e.getMessage().contains("/gone") && e.getMessage().contains("404"), e.getMessage());
    }

    @Test
    void missingDispatcherIsAnError() {
        ImportTag tag = new ImportTag();
        tag.setUrl("/nowhere");
        JspTagException e = assertThrows(JspTagException.class,
                () -> TagHarness.run(tag, TestPageContext.create(), Body.EMPTY));
        assertTrue(e.getMessage().contains("/nowhere"), e.getMessage());
    }

    @Test
    void dispatcherFailureIsWrapped() {
        TestPageContext pc = TestPageContext.create();
        req(pc).dispatchers().failWith("/boom", new IOException("disk"));
        ImportTag tag = new ImportTag();
        tag.setUrl("/boom");
        JspTagException e = assertThrows(JspTagException.class, () -> TagHarness.run(tag, pc, Body.EMPTY));
        assertInstanceOf(IOException.class, e.getCause());
    }

    @Test
    void foreignContextUsesTheForeignDispatcher() throws Exception {
        TestServletContext other = new TestServletContext().contextPath("/other");
        other.dispatchers().content("/f", "foreign");
        TestPageContext pc = TestPageContext.create();
        ((TestServletContext) pc.getServletContext()).addContext("/other", other);
        ImportTag tag = new ImportTag();
        tag.setUrl("/f");
        tag.setContext("/other");
        assertEquals("foreign", TagHarness.run(tag, pc, Body.EMPTY));
        ImportTag bad = new ImportTag();
        bad.setUrl("/f");
        bad.setContext("/unknown");
        assertThrows(JspTagException.class, () -> TagHarness.run(bad, pc, Body.EMPTY));
        ImportTag bad2 = new ImportTag();
        bad2.setUrl("f");
        bad2.setContext("/other");
        assertThrows(JspTagException.class, () -> TagHarness.run(bad2, pc, Body.EMPTY));
    }

    @Test
    void absoluteUrlGoesThroughExternalResources() throws Exception {
        List<String> asked = new ArrayList<>();
        ExternalResources fake = (url, enc) -> {
            asked.add(url + "|" + enc);
            return new StringReader("remote");
        };
        TestPageContext pc = TestPageContext.create();
        ImportTag tag = new ImportTag(fake);
        tag.setUrl("http://e.org/x");
        tag.setCharEncoding("UTF-8");
        String out = TagHarness.run(tag, pc, p -> UrlTagTest.addParam(p, tag, "a", "1"));
        assertEquals("remote", out);
        assertEquals(List.of("http://e.org/x?a=1|UTF-8"), asked);
    }

    @Test
    void absoluteFailureIsWrapped() {
        ExternalResources fake = (url, enc) -> {
            throw new IOException("HTTP 500");
        };
        ImportTag tag = new ImportTag(fake);
        tag.setUrl("http://e.org/x");
        JspTagException e = assertThrows(JspTagException.class,
                () -> TagHarness.run(tag, TestPageContext.create(), Body.EMPTY));
        assertTrue(e.getMessage().contains("http://e.org/x"), e.getMessage());
    }

    @Test
    void nullOrEmptyUrlIsAnError() {
        ImportTag tag = new ImportTag();
        assertThrows(JspTagException.class, () -> TagHarness.run(tag, TestPageContext.create(), Body.EMPTY));
        tag.setUrl("");
        assertThrows(JspTagException.class, () -> TagHarness.run(tag, TestPageContext.create(), Body.EMPTY));
    }

    @Test
    void reusedInstanceCarriesNoParameterOrReader() throws Exception {
        TestPageContext pc = TestPageContext.create();
        req(pc).dispatchers().content("/inc", "a").content("/inc?p=1", "withparam");
        ImportTag tag = new ImportTag();
        tag.setUrl("/inc");
        tag.setVar("v");
        TagHarness.run(tag, pc, p -> UrlTagTest.addParam(p, tag, "p", "1"));
        assertEquals("withparam", pc.getAttribute("v"));
        TagHarness.run(tag, pc, Body.EMPTY);
        assertEquals("a", pc.getAttribute("v"));
        tag.release();
        tag.setUrl("/inc");
        tag.setVarReader("r");
        TagHarness.run(tag, pc, Body.EMPTY);
        assertNull(pc.getAttribute("r"));
    }
}
