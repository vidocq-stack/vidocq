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
import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestPageContext;

import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.tagext.Tag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code <x:choose>}, {@code <x:when>}, {@code <x:otherwise>} (Tags 3.0 sections 12.3–12.5). */
class ChooseTagTest {

    private static final String MATCH = "$doc//a[@attr='attrvalue']";
    private static final String NO_MATCH = "$doc//a[@attr='ivalid']";

    private final TestPageContext pc = TestPageContext.create();

    @BeforeEach
    void document() throws Exception {
        pc.setAttribute("doc", XmlDocuments.parse(
                new StringReader("<a attr=\"attrvalue\">text</a>"), SystemIdPolicy.of("x:parse", null, pc), null));
    }

    private void when(Tag parent, String select, String text) throws Exception {
        WhenTag tag = new WhenTag();
        tag.setSelect(select);
        TagHarness.run(tag, pc, parent, p -> p.getOut().print(text));
    }

    private void otherwise(Tag parent, String text) throws Exception {
        TagHarness.run(new OtherwiseTag(), pc, parent, p -> p.getOut().print(text));
    }

    @Test
    void onlyTheFirstTrueWhenRenders() throws Exception {
        ChooseTag choose = new ChooseTag();

        String out = TagHarness.run(choose, pc, p -> {
            when(choose, NO_MATCH, "A");
            when(choose, MATCH, "B");
            when(choose, MATCH, "C");
            otherwise(choose, "O");
        });

        assertEquals("B", out);
    }

    @Test
    void otherwiseRendersWhenNoWhenMatched() throws Exception {
        ChooseTag choose = new ChooseTag();

        String out = TagHarness.run(choose, pc, p -> {
            when(choose, NO_MATCH, "A");
            when(choose, NO_MATCH, "B");
            otherwise(choose, "O");
        });

        assertEquals("O", out);
    }

    @Test
    void nothingRendersWhenNoWhenMatchedAndThereIsNoOtherwise() throws Exception {
        ChooseTag choose = new ChooseTag();

        assertEquals("", TagHarness.run(choose, pc, p -> when(choose, NO_MATCH, "A")));
    }

    @Test
    void whenUnderACoreChooseIsRejected() {
        io.vidocq.bodoni.core.tags.core.ChooseTag core = new io.vidocq.bodoni.core.tags.core.ChooseTag();

        JspTagException e = assertThrows(JspTagException.class, () -> when(core, MATCH, "A"));

        assertEquals("<x:when> must be a direct child of <x:choose>", e.getMessage());
    }

    @Test
    void whenWithoutParentIsRejected() {
        JspTagException e = assertThrows(JspTagException.class, () -> when(null, MATCH, "A"));

        assertEquals("<x:when> must be a direct child of <x:choose>", e.getMessage());
    }

    @Test
    void otherwiseUnderACoreChooseIsRejected() {
        io.vidocq.bodoni.core.tags.core.ChooseTag core = new io.vidocq.bodoni.core.tags.core.ChooseTag();

        JspTagException e = assertThrows(JspTagException.class, () -> otherwise(core, "O"));

        assertEquals("<x:otherwise> must be a direct child of <x:choose>", e.getMessage());
    }

    @Test
    void otherwiseWithoutParentIsRejected() {
        JspTagException e = assertThrows(JspTagException.class, () -> otherwise(null, "O"));

        assertEquals("<x:otherwise> must be a direct child of <x:choose>", e.getMessage());
    }

    @Test
    void aFailingWhenSelectIsATagExceptionKeepingTheCause() {
        ChooseTag choose = new ChooseTag();

        JspTagException e = assertThrows(JspTagException.class,
                () -> TagHarness.run(choose, pc, p -> when(choose, "$nope", "A")));

        assertTrue(e.getMessage().startsWith("x:when: cannot evaluate '$nope': "), e.getMessage());
        assertInstanceOf(XPathExpressionException.class, e.getCause());
    }

    @Test
    void aReusedChooseTakesABranchAgain() throws Exception {
        ChooseTag choose = new ChooseTag();
        WhenTag first = new WhenTag();
        first.setSelect(MATCH);
        OtherwiseTag other = new OtherwiseTag();

        for (int run = 0; run < 2; run++) {
            String out = TagHarness.run(choose, pc, p -> {
                TagHarness.run(first, pc, choose, q -> q.getOut().print("A"));
                TagHarness.run(other, pc, choose, q -> q.getOut().print("O"));
            });
            assertEquals("A", out);
        }
    }
}
