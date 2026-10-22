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

import io.vidocq.bodoni.core.testing.Body;
import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestPageContext;
import io.vidocq.bodoni.core.testing.TestValueExpression;

import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.PageContext;
import jakarta.servlet.jsp.jstl.core.IndexedValueExpression;
import jakarta.servlet.jsp.jstl.core.IteratedValueExpression;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ForEachTagTest {

    private static final Body PRINT_X = p -> p.getOut().print(p.getAttribute("x"));
    private static final Body PRINT_X_COMMA = p -> p.getOut().print(p.getAttribute("x") + ",");

    private static ForEachTag tag() {
        ForEachTag t = new ForEachTag();
        t.setVar("x");
        return t;
    }

    @Test
    void iteratesAList() throws Exception {
        ForEachTag t = tag();
        t.setItems(List.of("a", "b", "c"));
        assertEquals("abc", TagHarness.run(t, TestPageContext.create(), PRINT_X));
    }

    @Test
    void wrapsPrimitiveArrayElements() throws Exception {
        TestPageContext pc = TestPageContext.create();
        ForEachTag t = tag();
        t.setItems(new int[] {1, 2});
        List<Object> seen = new ArrayList<>();
        TagHarness.run(t, pc, p -> seen.add(p.getAttribute("x")));
        assertEquals(List.of(1, 2), seen);
        assertInstanceOf(Integer.class, seen.get(0));
    }

    @Test
    void iteratesObjectArraysAndOtherPrimitiveArrays() throws Exception {
        ForEachTag t = tag();
        t.setItems(new String[] {"p", "q"});
        assertEquals("pq", TagHarness.run(t, TestPageContext.create(), PRINT_X));
        t = tag();
        t.setItems(new boolean[] {true, false});
        assertEquals("truefalse", TagHarness.run(t, TestPageContext.create(), PRINT_X));
        t = tag();
        t.setItems(new char[] {'a', 'b'});
        assertEquals("ab", TagHarness.run(t, TestPageContext.create(), PRINT_X));
        t = tag();
        t.setItems(new double[] {1.5});
        assertEquals("1.5", TagHarness.run(t, TestPageContext.create(), PRINT_X));
    }

    @Test
    void mapYieldsEntries() throws Exception {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("k1", 1);
        m.put("k2", 2);
        ForEachTag t = tag();
        t.setItems(m);
        String out = TagHarness.run(t, TestPageContext.create(), p -> {
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) p.getAttribute("x");
            p.getOut().print(e.getKey() + "=" + e.getValue() + ";");
        });
        assertEquals("k1=1;k2=2;", out);
    }

    @Test
    void iteratesIteratorAndEnumeration() throws Exception {
        ForEachTag t = tag();
        t.setItems(List.of("a", "b").iterator());
        assertEquals("ab", TagHarness.run(t, TestPageContext.create(), PRINT_X));
        t = tag();
        t.setItems(Collections.enumeration(List.of("c", "d")));
        assertEquals("cd", TagHarness.run(t, TestPageContext.create(), PRINT_X));
    }

    @Test
    void stringItemsAreCommaSeparated() throws Exception {
        ForEachTag t = tag();
        t.setItems("a,b");
        assertEquals("a,b,", TagHarness.run(t, TestPageContext.create(), PRINT_X_COMMA));
    }

    @Test
    void nullItemsMeansNoIteration() throws Exception {
        ForEachTag t = tag();
        t.setItems(null);
        assertEquals("", TagHarness.run(t, TestPageContext.create(), PRINT_X));
    }

    @Test
    void beginAndEndSelectAnInclusiveRange() throws Exception {
        ForEachTag t = tag();
        t.setItems(List.of("a", "b", "c", "d"));
        t.setBegin(1);
        t.setEnd(2);
        assertEquals("bc", TagHarness.run(t, TestPageContext.create(), PRINT_X));
    }

    @Test
    void stepSkipsItems() throws Exception {
        ForEachTag t = tag();
        t.setItems(List.of("a", "b", "c", "d", "e"));
        t.setStep(2);
        assertEquals("ace", TagHarness.run(t, TestPageContext.create(), PRINT_X));
    }

    @Test
    void withoutItemsCountsIntegers() throws Exception {
        ForEachTag t = tag();
        t.setBegin(1);
        t.setEnd(3);
        List<Object> seen = new ArrayList<>();
        TagHarness.run(t, TestPageContext.create(), p -> seen.add(p.getAttribute("x")));
        assertEquals(List.of(1, 2, 3), seen);
    }

    @Test
    void negativeBeginIsRejected() {
        ForEachTag t = tag();
        t.setItems(List.of("a"));
        t.setBegin(-1);
        assertThrows(JspTagException.class, () -> TagHarness.run(t, TestPageContext.create(), PRINT_X));
    }

    @Test
    void zeroStepIsRejected() {
        ForEachTag t = tag();
        t.setItems(List.of("a"));
        t.setStep(0);
        assertThrows(JspTagException.class, () -> TagHarness.run(t, TestPageContext.create(), PRINT_X));
    }

    @Test
    void endBeforeBeginMeansNoIteration() throws Exception {
        ForEachTag t = tag();
        t.setBegin(3);
        t.setEnd(1);
        assertEquals("", TagHarness.run(t, TestPageContext.create(), PRINT_X));
    }

    @Test
    void unsupportedItemsTypeIsRejected() {
        ForEachTag t = tag();
        t.setItems(new Object());
        assertThrows(JspTagException.class, () -> TagHarness.run(t, TestPageContext.create(), PRINT_X));
    }

    @Test
    void varStatusExposesTheLoopState() throws Exception {
        ForEachTag t = tag();
        t.setVarStatus("s");
        t.setItems(List.of("a", "b", "c"));
        String out = TagHarness.run(t, TestPageContext.create(), p -> {
            var s = (jakarta.servlet.jsp.jstl.core.LoopTagStatus) p.getAttribute("s");
            p.getOut().print(s.getIndex() + ":" + s.getCount() + ":" + s.isFirst() + ":" + s.isLast() + ";");
        });
        assertEquals("0:1:true:false;1:2:false:false;2:3:false:true;", out);
    }

    @Test
    void variablesAreRemovedAfterTheLoop() throws Exception {
        TestPageContext pc = TestPageContext.create();
        ForEachTag t = tag();
        t.setVarStatus("s");
        t.setItems(List.of("a"));
        TagHarness.run(t, pc, Body.EMPTY);
        assertNull(pc.getAttribute("x", PageContext.PAGE_SCOPE));
        assertNull(pc.getAttribute("s", PageContext.PAGE_SCOPE));
    }

    @Test
    void variablesAreRemovedWhenTheBodyThrows() {
        TestPageContext pc = TestPageContext.create();
        ForEachTag t = tag();
        t.setVarStatus("s");
        t.setItems(List.of("a", "b"));
        assertThrows(IllegalStateException.class, () -> TagHarness.run(t, pc, p -> {
            throw new IllegalStateException("boom");
        }));
        assertNull(pc.getAttribute("x", PageContext.PAGE_SCOPE));
        assertNull(pc.getAttribute("s", PageContext.PAGE_SCOPE));
    }

    @Test
    void reusedInstanceFollowsTheNewAttributes() throws Exception {
        ForEachTag t = tag();
        t.setItems(List.of("a", "b"));
        assertEquals("ab", TagHarness.run(t, TestPageContext.create(), PRINT_X));
        t.setItems(List.of("c", "d", "e"));
        t.setBegin(1);
        assertEquals("de", TagHarness.run(t, TestPageContext.create(), PRINT_X));
        t.setItems(List.of("f", "g"));
        t.setBegin(0);
        assertEquals("fg", TagHarness.run(t, TestPageContext.create(), PRINT_X));
    }

    @Test
    void deferredListItemsMapVarToIndexedValueExpressions() throws Exception {
        TestPageContext pc = TestPageContext.create();
        ForEachTag t = tag();
        t.setItems(new TestValueExpression(List.of("a", "b")));
        List<Object> seen = new ArrayList<>();
        TagHarness.run(t, pc, p -> seen.add(pc.getELContext().getVariableMapper().resolveVariable("x")));
        assertEquals(2, seen.size());
        assertInstanceOf(IndexedValueExpression.class, seen.get(0));
        assertNull(pc.getELContext().getVariableMapper().resolveVariable("x"));
    }

    @Test
    void deferredNonListItemsMapVarToIteratedValueExpressions() throws Exception {
        TestPageContext pc = TestPageContext.create();
        ForEachTag t = tag();
        t.setItems(new TestValueExpression(new java.util.LinkedHashSet<>(List.of("a", "b"))));
        List<Object> seen = new ArrayList<>();
        TagHarness.run(t, pc, p -> seen.add(pc.getELContext().getVariableMapper().resolveVariable("x")));
        assertEquals(2, seen.size());
        assertInstanceOf(IteratedValueExpression.class, seen.get(0));
    }
}
