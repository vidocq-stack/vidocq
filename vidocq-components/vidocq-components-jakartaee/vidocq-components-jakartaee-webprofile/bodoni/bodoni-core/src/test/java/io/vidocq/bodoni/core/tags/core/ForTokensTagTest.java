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

import io.vidocq.bodoni.core.testing.Body;
import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestPageContext;
import io.vidocq.bodoni.core.testing.TestValueExpression;

import jakarta.servlet.jsp.jstl.core.IteratedValueExpression;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class ForTokensTagTest {

    private static final Body PRINT_X = p -> p.getOut().print(p.getAttribute("x") + ",");

    private static ForTokensTag tag(String items, String delims) {
        ForTokensTag t = new ForTokensTag();
        t.setVar("x");
        t.setItems(items);
        t.setDelims(delims);
        return t;
    }

    @Test
    void splitsOnAnyDelimiterCharacter() throws Exception {
        assertEquals("a,b,c,", TagHarness.run(tag("a|b;c", "|;"), TestPageContext.create(), PRINT_X));
    }

    @Test
    void nullItemsMeansNoIteration() throws Exception {
        assertEquals("", TagHarness.run(tag(null, ","), TestPageContext.create(), PRINT_X));
    }

    @Test
    void beginEndAndStepApplyToTokens() throws Exception {
        ForTokensTag t = tag("a,b,c,d,e", ",");
        t.setBegin(1);
        t.setEnd(4);
        t.setStep(2);
        assertEquals("b,d,", TagHarness.run(t, TestPageContext.create(), PRINT_X));
    }

    @Test
    void negativeEndMeansNoIteration() throws Exception {
        ForTokensTag t = tag("a,b", ",");
        t.setBegin(0);
        t.setEnd(-1);
        assertEquals("", TagHarness.run(t, TestPageContext.create(), PRINT_X));
    }

    @Test
    void deferredItemsMapVarToIteratedValueExpressionsOverTheTokens() throws Exception {
        TestPageContext pc = TestPageContext.create();
        ForTokensTag t = new ForTokensTag();
        t.setItems(new TestValueExpression("[Tiger Woods][Pebble Beach]"));
        t.setDelims("[]");
        t.setVar("x");
        List<Object> seen = new ArrayList<>();
        TagHarness.run(t, pc, p -> seen.add(pc.getELContext().getVariableMapper().resolveVariable("x")));
        assertEquals(2, seen.size());
        assertInstanceOf(IteratedValueExpression.class, seen.get(0));
        assertInstanceOf(IteratedValueExpression.class, seen.get(1));
        assertEquals("Tiger Woods", ((jakarta.el.ValueExpression) seen.get(0)).getValue(pc.getELContext()));
        assertEquals("Pebble Beach", ((jakarta.el.ValueExpression) seen.get(1)).getValue(pc.getELContext()));
        assertNull(pc.getELContext().getVariableMapper().resolveVariable("x"));
    }

    @Test
    void nonStringItemsAreCoercedToString() throws Exception {
        assertEquals("42,", runWith(42));
        assertEquals("MONDAY,", runWith(new TestValueExpression(java.time.DayOfWeek.MONDAY)));
        assertEquals("42,", runWith(new TestValueExpression(42)));
    }

    @Test
    void nullDeferredItemsIterateZeroTimes() throws Exception {
        assertEquals("", runWith(new TestValueExpression(null)));
    }

    private static String runWith(Object items) throws Exception {
        ForTokensTag t = new ForTokensTag();
        t.setVar("x");
        t.setItems(items);
        t.setDelims(",");
        TestPageContext pc = TestPageContext.create();
        return TagHarness.run(t, pc, p -> {
            Object x = pc.getELContext().getVariableMapper().resolveVariable("x");
            p.getOut().print((x instanceof jakarta.el.ValueExpression e ? e.getValue(pc.getELContext())
                    : p.getAttribute("x")) + ",");
        });
    }

    @Test
    void reusedInstanceFollowsTheNewAttributes() throws Exception {
        ForTokensTag t = tag("a,b", ",");
        assertEquals("a,b,", TagHarness.run(t, TestPageContext.create(), PRINT_X));
        t.setItems("c d");
        t.setDelims(" ");
        assertEquals("c,d,", TagHarness.run(t, TestPageContext.create(), PRINT_X));
    }
}
