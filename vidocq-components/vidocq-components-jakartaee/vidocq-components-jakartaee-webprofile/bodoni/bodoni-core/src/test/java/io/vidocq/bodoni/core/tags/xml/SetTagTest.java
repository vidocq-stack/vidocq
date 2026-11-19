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
package io.vidocq.bodoni.core.tags.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;

import javax.xml.xpath.XPathExpressionException;

import io.vidocq.bodoni.core.internal.SystemIdPolicy;
import io.vidocq.bodoni.core.internal.XmlDocuments;
import io.vidocq.bodoni.core.testing.Body;
import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestPageContext;

import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.PageContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.NodeList;

/** {@code <x:set>} (Tags 3.0 section 11.4, XPath to Java types of section 11.1.4). */
class SetTagTest {

    private final TestPageContext pc = TestPageContext.create();

    @BeforeEach
    void document() throws Exception {
        pc.setAttribute("d", XmlDocuments.parse(
                new StringReader("<parent><a>text</a><b>1</b><b>2</b></parent>"), SystemIdPolicy.of("x:parse", null, pc), null));
    }

    private Object set(String select) throws Exception {
        SetTag tag = new SetTag();
        tag.setSelect(select);
        tag.setVar("r");
        TagHarness.run(tag, pc, Body.NONE);
        return pc.getAttribute("r");
    }

    @Test
    void aBooleanIsABoolean() throws Exception {
        assertEquals(Boolean.TRUE, set("boolean($d//b)"));
    }

    @Test
    void aNumberIsANumber() throws Exception {
        assertEquals(2.0, assertInstanceOf(Number.class, set("count($d//b)")).doubleValue());
    }

    @Test
    void aStringIsAString() throws Exception {
        assertEquals("text", set("string($d//a)"));
    }

    @Test
    void aNodeSetIsANodeList() throws Exception {
        NodeList nodes = assertInstanceOf(NodeList.class, set("$d//b"));
        assertEquals(2, nodes.getLength());

        pc.setAttribute("nodes", nodes);
        OutTag out = new OutTag();
        out.setSelect("$nodes[2]");

        assertEquals("2", TagHarness.run(out, pc, Body.NONE));
    }

    @Test
    void aSelectedNodeSetIsUsableAsAVariable() throws Exception {
        set("$d//parent");

        OutTag out = new OutTag();
        out.setSelect("$r//a");

        assertEquals("text", TagHarness.run(out, pc, Body.NONE));
    }

    @Test
    void scopeAppliesToVar() throws Exception {
        SetTag tag = new SetTag();
        tag.setSelect("string($d//a)");
        tag.setVar("r");
        tag.setScope("session");

        TagHarness.run(tag, pc, Body.NONE);

        assertEquals("text", pc.getAttribute("r", PageContext.SESSION_SCOPE));
    }

    @Test
    void anInvalidScopeIsATagException() {
        SetTag tag = new SetTag();
        tag.setSelect("1");
        tag.setVar("r");
        tag.setScope("galaxy");

        JspTagException e = assertThrows(JspTagException.class, () -> TagHarness.run(tag, pc, Body.NONE));

        assertTrue(e.getMessage().contains("galaxy"), e.getMessage());
    }

    @Test
    void aFailingSelectIsATagExceptionKeepingTheCause() {
        SetTag tag = new SetTag();
        tag.setSelect("$nope");
        tag.setVar("r");

        JspTagException e = assertThrows(JspTagException.class, () -> TagHarness.run(tag, pc, Body.NONE));

        assertTrue(e.getMessage().startsWith("x:set: cannot evaluate '$nope': "), e.getMessage());
        assertInstanceOf(XPathExpressionException.class, e.getCause());
    }
}
