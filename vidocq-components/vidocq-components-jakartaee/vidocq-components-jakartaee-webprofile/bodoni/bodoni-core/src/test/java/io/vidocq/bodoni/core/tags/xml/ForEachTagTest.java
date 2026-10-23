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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import javax.xml.xpath.XPathExpressionException;

import io.vidocq.bodoni.core.internal.SystemIdPolicy;
import io.vidocq.bodoni.core.internal.XmlDocuments;
import io.vidocq.bodoni.core.testing.Body;
import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestPageContext;

import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.PageContext;
import jakarta.servlet.jsp.jstl.core.LoopTagStatus;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Node;

/** {@code <x:forEach>} (Tags 3.0 section 12.6). */
class ForEachTagTest {

    private static final String ALL_B = "$d//b";
    private static final String ITER = "iter";

    /** Prints the text of the current {@code iter} node. */
    private static final Body PRINT_ITER = p -> p.getOut().print(((Node) p.getAttribute(ITER)).getTextContent());

    private final TestPageContext pc = TestPageContext.create();

    private void document(String xml) throws Exception {
        pc.setAttribute("d", XmlDocuments.parse(new StringReader(xml), SystemIdPolicy.of("x:parse", null, pc), null));
    }

    private void twoB() throws Exception {
        document("<a><b>btext1</b><b>btext2</b></a>");
    }

    private void fiveB() throws Exception {
        document("<a><b>0</b><b>1</b><b>2</b><b>3</b><b>4</b></a>");
    }

    private static ForEachTag forEach(String select) {
        ForEachTag tag = new ForEachTag();
        tag.setSelect(select);
        tag.setVar(ITER);
        return tag;
    }

    private static OutTag out(String select) {
        OutTag tag = new OutTag();
        tag.setSelect(select);
        return tag;
    }

    @Test
    void theBodyRunsOncePerSelectedNode() throws Exception {
        twoB();
        ForEachTag tag = new ForEachTag();
        tag.setSelect(ALL_B);

        assertEquals("xx", TagHarness.run(tag, pc, p -> p.getOut().print("x")));
    }

    @Test
    void varIsTheCurrentNodeAndIsRemovedAfterTheLoop() throws Exception {
        twoB();
        pc.setAttribute(ITER, "nested var");
        List<Object> seen = new ArrayList<>();

        TagHarness.run(forEach(ALL_B), pc, p -> seen.add(p.getAttribute(ITER)));

        assertEquals(2, seen.size());
        assertEquals("btext1", assertInstanceOf(Node.class, seen.get(0)).getTextContent());
        assertEquals("btext2", assertInstanceOf(Node.class, seen.get(1)).getTextContent());
        assertNull(pc.getAttribute(ITER, PageContext.PAGE_SCOPE));
    }

    @Test
    void aPooledHandlerHoldsNoNodeAfterTheLoop() throws Exception {
        twoB();
        ForEachTag tag = forEach(ALL_B);
        tag.setEnd(0);

        assertEquals("btext1", TagHarness.run(tag, pc, PRINT_ITER));

        Field nodes = ForEachTag.class.getDeclaredField("nodes");
        nodes.setAccessible(true);
        assertNull(nodes.get(tag));
    }

    @Test
    void theCurrentNodeIsTheContextOfNestedExpressions() throws Exception {
        twoB();
        ForEachTag tag = new ForEachTag();
        tag.setSelect(ALL_B);

        String text = TagHarness.run(tag, pc, p -> TagHarness.run(out("."), pc, tag, Body.NONE));

        assertEquals("btext1btext2", text);
    }

    @Test
    void aNestedForEachIteratesTheChildrenOfTheOuterNode() throws Exception {
        document("<r><a><b>1</b><b>2</b></a><a><b>3</b></a></r>");
        ForEachTag outer = new ForEachTag();
        outer.setSelect("$d/r/a");
        ForEachTag inner = new ForEachTag();
        inner.setSelect("b");

        String text = TagHarness.run(outer, pc, p -> {
            TagHarness.run(inner, pc, outer, q -> TagHarness.run(out("."), pc, inner, Body.NONE));
            p.getOut().print("|");
        });

        assertEquals("12|3|", text);
    }

    @Test
    void varStatusIsALoopTagStatusRemovedAfterTheLoop() throws Exception {
        twoB();
        ForEachTag tag = new ForEachTag();
        tag.setSelect(ALL_B);
        tag.setVarStatus("s");

        String text = TagHarness.run(tag, pc, p -> {
            LoopTagStatus s = assertInstanceOf(LoopTagStatus.class, p.getAttribute("s"));
            p.getOut().print(s.getCount() + ":" + s.isFirst() + ":" + s.isLast() + ";");
        });

        assertEquals("1:true:false;2:false:true;", text);
        assertNull(pc.getAttribute("s", PageContext.PAGE_SCOPE));
    }

    @Test
    void beginSkipsTheFirstNodes() throws Exception {
        fiveB();
        ForEachTag tag = forEach(ALL_B);
        tag.setBegin(1);

        assertEquals("1234", TagHarness.run(tag, pc, PRINT_ITER));
    }

    @Test
    void endIsInclusive() throws Exception {
        fiveB();
        ForEachTag tag = forEach(ALL_B);
        tag.setEnd(2);

        assertEquals("012", TagHarness.run(tag, pc, PRINT_ITER));
    }

    @Test
    void stepSkipsNodes() throws Exception {
        fiveB();
        ForEachTag tag = forEach(ALL_B);
        tag.setStep(2);

        assertEquals("024", TagHarness.run(tag, pc, PRINT_ITER));
    }

    @Test
    void endBeforeBeginMeansNoIteration() throws Exception {
        fiveB();
        ForEachTag tag = forEach(ALL_B);
        tag.setBegin(3);
        tag.setEnd(1);

        assertEquals("", TagHarness.run(tag, pc, PRINT_ITER));
    }

    @Test
    void aNegativeBeginIsRejected() throws Exception {
        fiveB();
        ForEachTag tag = forEach(ALL_B);
        tag.setBegin(-1);

        assertThrows(JspTagException.class, () -> TagHarness.run(tag, pc, PRINT_ITER));
    }

    @Test
    void aZeroStepIsRejected() throws Exception {
        fiveB();
        ForEachTag tag = forEach(ALL_B);
        tag.setStep(0);

        assertThrows(JspTagException.class, () -> TagHarness.run(tag, pc, PRINT_ITER));
    }

    @Test
    void anEmptySelectIsRejected() {
        ForEachTag tag = forEach("");

        JspTagException e = assertThrows(JspTagException.class, () -> TagHarness.run(tag, pc, PRINT_ITER));

        assertEquals("x:forEach: select is empty", e.getMessage());
    }

    @Test
    void aMissingSelectIsRejected() {
        ForEachTag tag = forEach(null);

        JspTagException e = assertThrows(JspTagException.class, () -> TagHarness.run(tag, pc, PRINT_ITER));

        assertEquals("x:forEach: select is empty", e.getMessage());
    }

    @Test
    void aFailingSelectIsATagExceptionKeepingTheCause() {
        ForEachTag tag = forEach("$nope");

        JspTagException e = assertThrows(JspTagException.class, () -> TagHarness.run(tag, pc, PRINT_ITER));

        assertTrue(e.getMessage().startsWith("x:forEach: cannot evaluate '$nope': "), e.getMessage());
        assertInstanceOf(XPathExpressionException.class, e.getCause());
    }

    @Test
    void aSelectThatIsNotANodeSetIsRejected() {
        ForEachTag tag = forEach("1");

        JspTagException e = assertThrows(JspTagException.class, () -> TagHarness.run(tag, pc, PRINT_ITER));

        assertInstanceOf(XPathExpressionException.class, e.getCause());
    }

    @Test
    void varAndVarStatusAreRemovedWhenTheBodyThrows() throws Exception {
        twoB();
        ForEachTag tag = forEach(ALL_B);
        tag.setVarStatus("s");

        assertThrows(IllegalStateException.class, () -> TagHarness.run(tag, pc, p -> {
            throw new IllegalStateException("boom");
        }));

        assertNull(pc.getAttribute(ITER, PageContext.PAGE_SCOPE));
        assertNull(pc.getAttribute("s", PageContext.PAGE_SCOPE));
    }

    @Test
    void aReusedInstanceIteratesAgain() throws Exception {
        twoB();
        ForEachTag tag = forEach(ALL_B);

        assertEquals("btext1btext2", TagHarness.run(tag, pc, PRINT_ITER));
        assertEquals("btext1btext2", TagHarness.run(tag, pc, PRINT_ITER));
    }
}
