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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@code <x:if>} (Tags 3.0 section 12.2), its test converted by XPath {@code boolean()} (section 12.1). */
class IfTagTest {

    private static final Body BODY = p -> p.getOut().print("body");

    private final TestPageContext pc = TestPageContext.create();

    @BeforeEach
    void document() throws Exception {
        pc.setAttribute("d", XmlDocuments.parse(
                new StringReader("<a><b/><b/></a>"), SystemIdPolicy.of("x:parse", null, pc), null));
    }

    private static IfTag test(String select) {
        IfTag tag = new IfTag();
        tag.setSelect(select);
        return tag;
    }

    private String run(IfTag tag) throws Exception {
        return TagHarness.run(tag, pc, BODY);
    }

    @Test
    void aNonEmptyNodeSetRendersTheBody() throws Exception {
        assertEquals("body", run(test("$d//b")));
    }

    @Test
    void anEmptyNodeSetSkipsTheBody() throws Exception {
        assertEquals("", run(test("$d//zz")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0 div 0", "''"})
    void zeroNaNAndTheEmptyStringAreFalse(String select) throws Exception {
        assertEquals("", run(test(select)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"'x'", "1"})
    void aNonEmptyStringAndANonZeroNumberAreTrue(String select) throws Exception {
        assertEquals("body", run(test(select)));
    }

    @Test
    void exportsTheResultAsABooleanInTheRequestedScope() throws Exception {
        IfTag tag = test("$d//zz");
        tag.setVar("r");
        tag.setScope("request");

        TagHarness.run(tag, pc, Body.NONE);

        assertFalse(assertInstanceOf(Boolean.class, pc.getAttribute("r", PageContext.REQUEST_SCOPE)));
    }

    @Test
    void aFailingSelectIsATagExceptionKeepingTheCause() {
        IfTag tag = test("$nope");

        JspTagException e = assertThrows(JspTagException.class, () -> run(tag));

        assertTrue(e.getMessage().startsWith("x:if: cannot evaluate '$nope': "), e.getMessage());
        assertInstanceOf(XPathExpressionException.class, e.getCause());
    }

    @Test
    void anInvalidScopeIsATagException() {
        IfTag tag = test("$d//b");
        tag.setVar("r");
        tag.setScope("invalid");

        JspTagException e = assertThrows(JspTagException.class, () -> run(tag));

        assertTrue(e.getMessage().startsWith("x:if: "), e.getMessage());
        assertNull(pc.getAttribute("r", PageContext.PAGE_SCOPE));
    }

    @Test
    void releaseForgetsTheScope() throws Exception {
        IfTag tag = test("$d//b");
        tag.setScope("invalid");
        tag.release();
        tag.setSelect("$d//b");
        tag.setVar("r");

        assertEquals("body", run(tag));
        assertTrue(assertInstanceOf(Boolean.class, pc.getAttribute("r", PageContext.PAGE_SCOPE)));
    }

    @Test
    void aReusedInstanceEvaluatesItsSelectAgain() throws Exception {
        IfTag tag = test("$d//b");
        tag.setVar("r");

        assertEquals("body", run(tag));
        pc.setAttribute("d", XmlDocuments.parse(
                new StringReader("<a/>"), SystemIdPolicy.of("x:parse", null, pc), null));

        assertEquals("", run(tag));
        assertFalse(assertInstanceOf(Boolean.class, pc.getAttribute("r", PageContext.PAGE_SCOPE)));
    }
}
