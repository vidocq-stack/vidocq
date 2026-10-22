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
import static org.junit.jupiter.api.Assertions.assertSame;
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

    private static ImportTag importOf(String url) {
        ImportTag tag = new ImportTag();
        tag.setUrl(url);
        return tag;
    }

    @Test
    void redirectFromTheIncludedResourceNeverReachesTheRealResponse() {
        String[] overloads = {"a", "b", "c", "d"};
        for (String name : overloads) {
            TestPageContext pc = TestPageContext.create();
            req(pc).dispatchers().action("/r", (rq, rs) -> {
                jakarta.servlet.http.HttpServletResponse http = (jakarta.servlet.http.HttpServletResponse) rs;
                switch (name) {
                    case "a" -> http.sendRedirect("x");
                    case "b" -> http.sendRedirect("x", 301);
                    case "c" -> http.sendRedirect("x", true);
                    default -> http.sendRedirect("x", 303, false);
                }
            });
            JspTagException e = assertThrows(JspTagException.class,
                    () -> TagHarness.run(importOf("/r"), pc, Body.EMPTY));
            assertTrue(e.getMessage().contains("/r"), e.getMessage());
            io.vidocq.bodoni.core.testing.TestHttpServletResponse real =
                    (io.vidocq.bodoni.core.testing.TestHttpServletResponse) pc.getResponse();
            assertNull(real.redirect(), name);
            assertEquals(200, real.getStatus(), name);
        }
    }

    @Test
    void resetOperationsActOnTheCaptureOnly() throws Exception {
        TestPageContext pc = TestPageContext.create();
        req(pc).dispatchers().action("/s", (rq, rs) -> {
            jakarta.servlet.http.HttpServletResponse http = (jakarta.servlet.http.HttpServletResponse) rs;
            rs.getWriter().write("junk");
            rs.resetBuffer();
            rs.getWriter().write("kept");
            rs.flushBuffer();
            http.setStatus(500);
            rs.reset();
            rs.getWriter().write("final");
        });
        assertEquals("final", TagHarness.run(importOf("/s"), pc, Body.EMPTY));
        assertEquals(200, ((io.vidocq.bodoni.core.testing.TestHttpServletResponse) pc.getResponse()).getStatus());
    }

    @Test
    void runtimeExceptionIsWrappedWithTheCause() {
        TestPageContext pc = TestPageContext.create();
        IllegalStateException cause = new IllegalStateException("bad");
        req(pc).dispatchers().failWith("/x", cause);
        JspTagException e = assertThrows(JspTagException.class, () -> TagHarness.run(importOf("/x"), pc, Body.EMPTY));
        assertSame(cause, e.getCause());
        assertSame(cause, e.getCause());
    }

    @Test
    void servletExceptionWithRootCauseExposesIt() {
        TestPageContext pc = TestPageContext.create();
        IOException root = new IOException("disk full");
        req(pc).dispatchers().failWith("/x", new jakarta.servlet.ServletException("outer", root));
        JspTagException e = assertThrows(JspTagException.class, () -> TagHarness.run(importOf("/x"), pc, Body.EMPTY));
        assertSame(root, e.getCause());
        assertTrue(e.getMessage().contains("disk full"), e.getMessage());
    }

    @Test
    void servletExceptionWithoutRootCauseIsWrappedLikeIo() {
        TestPageContext pc = TestPageContext.create();
        jakarta.servlet.ServletException cause = new jakarta.servlet.ServletException("plain");
        req(pc).dispatchers().failWith("/x", cause);
        JspTagException e = assertThrows(JspTagException.class, () -> TagHarness.run(importOf("/x"), pc, Body.EMPTY));
        assertSame(cause, e.getCause());
        assertTrue(e.getMessage().contains("plain"), e.getMessage());
    }

    @Test
    void sessionIdIsStrippedBeforeDispatching() throws Exception {
        TestPageContext pc = TestPageContext.create();
        req(pc).dispatchers().content("/inc?q=1", "ok");
        assertEquals("ok", TagHarness.run(importOf("/inc;JSESSIONID=ABC123?q=1"), pc, Body.EMPTY));
        assertEquals(List.of("/inc?q=1"), req(pc).dispatchers().calls());
    }

    @Test
    void bytesAreDecodedWithTheCharsetDeclaredByTheResource() throws Exception {
        TestPageContext pc = TestPageContext.create();
        pc.getResponse().setCharacterEncoding("ISO-8859-1");
        req(pc).dispatchers().action("/u", (rq, rs) -> {
            rs.setContentType("text/plain; charset=UTF-8");
            rs.getOutputStream().write("\u00e9".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        });
        assertEquals("\u00e9", TagHarness.run(importOf("/u"), pc, Body.EMPTY));
        TestPageContext pc2 = TestPageContext.create();
        req(pc2).dispatchers().action("/u", (rq, rs) -> {
            rs.setCharacterEncoding("UTF-8");
            rs.getOutputStream().write("\u00e9".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        });
        assertEquals("\u00e9", TagHarness.run(importOf("/u"), pc2, Body.EMPTY));
    }

    @Test
    void undeclaredBytesAreIso88591NotThePageEncoding() throws Exception {
        TestPageContext pc = TestPageContext.create();
        pc.getResponse().setCharacterEncoding("UTF-8");
        req(pc).dispatchers().action("/u", (rq, rs) -> rs.getOutputStream().write(0xE9));
        assertEquals("\u00e9", TagHarness.run(importOf("/u"), pc, Body.EMPTY));
        ImportTag explicit = importOf("/u");
        explicit.setCharEncoding("UTF-16BE");
        req(pc).dispatchers().action("/w", (rq, rs) -> rs.getOutputStream().write(new byte[] {0, 'a'}));
        explicit.setUrl("/w");
        assertEquals("a", TagHarness.run(explicit, pc, Body.EMPTY));
    }

    @Test
    void reusedAsAnEmptyElementKeepsNoStaleBodyContent() throws Exception {
        TestPageContext pc = TestPageContext.create();
        req(pc).dispatchers().content("/inc", "hello");
        ImportTag tag = new ImportTag();
        tag.setUrl("/inc");
        assertEquals("hello", TagHarness.run(tag, pc, p -> p.getOut().print("ignored")));
        assertEquals("hello", TagHarness.run(tag, pc, Body.NONE));
        assertNull(tag.getBodyContent());
    }
}
