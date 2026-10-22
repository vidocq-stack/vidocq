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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;

import io.vidocq.bodoni.core.testing.Body;
import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestBean;
import io.vidocq.bodoni.core.testing.TestPageContext;

import jakarta.el.ELContext;
import jakarta.el.ValueExpression;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.PageContext;

import org.junit.jupiter.api.Test;

class SetTagTest {

    private static final class FixedExpression extends ValueExpression {
        private static final long serialVersionUID = 1L;

        @Override
        public Object getValue(ELContext context) {
            return "x";
        }

        @Override
        public void setValue(ELContext context, Object value) {
            // a test double: nothing to do
        }

        @Override
        public boolean isReadOnly(ELContext context) {
            return true;
        }

        @Override
        public Class<?> getType(ELContext context) {
            return Object.class;
        }

        @Override
        public Class<?> getExpectedType() {
            return Object.class;
        }

        @Override
        public String getExpressionString() {
            return "${x}";
        }

        @Override
        public boolean equals(Object obj) {
            return obj == this;
        }

        @Override
        public int hashCode() {
            return 1;
        }

        @Override
        public boolean isLiteralText() {
            return false;
        }
    }

    @Test
    void setsPageAttributeByDefault() throws Exception {
        TestPageContext pc = TestPageContext.create();
        SetTag tag = new SetTag();
        tag.setVar("x");
        tag.setValue("v");
        TagHarness.run(tag, pc, Body.EMPTY);
        assertEquals("v", pc.getAttribute("x", PageContext.PAGE_SCOPE));
    }

    @Test
    void setsRequestAttributeWithScope() throws Exception {
        TestPageContext pc = TestPageContext.create();
        SetTag tag = new SetTag();
        tag.setVar("x");
        tag.setScope("request");
        tag.setValue("v");
        TagHarness.run(tag, pc, Body.EMPTY);
        assertEquals("v", pc.getRequest().getAttribute("x"));
        assertNull(pc.getAttribute("x", PageContext.PAGE_SCOPE));
    }

    @Test
    void nullValueWithScopeRemovesFromThatScopeOnly() throws Exception {
        TestPageContext pc = TestPageContext.create();
        pc.setAttribute("x", "p", PageContext.PAGE_SCOPE);
        pc.setAttribute("x", "r", PageContext.REQUEST_SCOPE);
        SetTag tag = new SetTag();
        tag.setVar("x");
        tag.setScope("request");
        tag.setValue(null);
        TagHarness.run(tag, pc, Body.EMPTY);
        assertNull(pc.getAttribute("x", PageContext.REQUEST_SCOPE));
        assertEquals("p", pc.getAttribute("x", PageContext.PAGE_SCOPE));
    }

    @Test
    void nullValueWithoutScopeRemovesFromAllScopes() throws Exception {
        TestPageContext pc = TestPageContext.create();
        pc.setAttribute("x", "p", PageContext.PAGE_SCOPE);
        pc.setAttribute("x", "r", PageContext.REQUEST_SCOPE);
        pc.setAttribute("x", "s", PageContext.SESSION_SCOPE);
        pc.setAttribute("x", "a", PageContext.APPLICATION_SCOPE);
        SetTag tag = new SetTag();
        tag.setVar("x");
        tag.setValue(null);
        TagHarness.run(tag, pc, Body.EMPTY);
        assertNull(pc.findAttribute("x"));
    }

    @Test
    void bodyContentIsTrimmedWhenNoValue() throws Exception {
        TestPageContext pc = TestPageContext.create();
        SetTag tag = new SetTag();
        tag.setVar("x");
        String out = TagHarness.run(tag, pc, p -> p.getOut().print("  t  "));
        assertEquals("t", pc.getAttribute("x"));
        assertEquals("", out);
    }

    @Test
    void reusedAsAnEmptyElementDoesNotSetThePreviousBody() throws Exception {
        // A pooled handler: <c:set var="x">first</c:set>, then <c:set var="x"/>, which gets
        // no setBodyContent from the container: the value is "" (Tags 3.0 §4.3).
        TestPageContext pc = TestPageContext.create();
        SetTag tag = new SetTag();
        tag.setVar("x");
        TagHarness.run(tag, pc, p -> p.getOut().print("first"));
        assertEquals("first", pc.getAttribute("x"));
        TagHarness.run(tag, pc, Body.NONE);
        assertEquals("", pc.getAttribute("x"));
    }

    @Test
    void emptyBodyGivesEmptyString() throws Exception {
        TestPageContext pc = TestPageContext.create();
        SetTag tag = new SetTag();
        tag.setVar("x");
        TagHarness.run(tag, pc, Body.EMPTY);
        assertEquals("", pc.getAttribute("x"));
    }

    @Test
    void mapTargetReceivesProperty() throws Exception {
        TestPageContext pc = TestPageContext.create();
        Map<String, Object> map = new HashMap<>();
        SetTag tag = new SetTag();
        tag.setTarget(map);
        tag.setProperty("k");
        tag.setValue("v");
        TagHarness.run(tag, pc, Body.EMPTY);
        assertEquals("v", map.get("k"));

        SetTag remove = new SetTag();
        remove.setTarget(map);
        remove.setProperty("k");
        remove.setValue(null);
        TagHarness.run(remove, pc, Body.EMPTY);
        assertEquals(false, map.containsKey("k"));
    }

    @Test
    void beanTargetIsSetThroughTheElResolver() throws Exception {
        TestPageContext pc = TestPageContext.create();
        TestBean bean = new TestBean();
        SetTag tag = new SetTag();
        tag.setTarget(bean);
        tag.setProperty("name");
        tag.setValue("Ada");
        TagHarness.run(tag, pc, Body.EMPTY);
        assertEquals("Ada", bean.getName());
    }

    @Test
    void targetWithBodyValue() throws Exception {
        TestPageContext pc = TestPageContext.create();
        Map<String, Object> map = new HashMap<>();
        SetTag tag = new SetTag();
        tag.setTarget(map);
        tag.setProperty("k");
        TagHarness.run(tag, pc, p -> p.getOut().print(" body "));
        assertEquals("body", map.get("k"));
    }

    @Test
    void nullTargetIsRejected() {
        SetTag tag = new SetTag();
        tag.setTarget(null);
        tag.setProperty("k");
        tag.setValue("v");
        // target attribute present but evaluated to null: distinguish from absent
        assertThrows(JspTagException.class, () -> TagHarness.run(tag, TestPageContext.create(), Body.EMPTY));
    }

    @Test
    void stringTargetIsRejected() {
        SetTag tag = new SetTag();
        tag.setTarget("a string");
        tag.setProperty("length");
        tag.setValue("v");
        assertThrows(JspTagException.class, () -> TagHarness.run(tag, TestPageContext.create(), Body.EMPTY));
    }

    @Test
    void missingVarAndTargetIsRejected() {
        SetTag tag = new SetTag();
        tag.setValue("v");
        assertThrows(JspTagException.class, () -> TagHarness.run(tag, TestPageContext.create(), Body.EMPTY));
    }

    @Test
    void invalidScopeIsRejected() {
        SetTag tag = new SetTag();
        tag.setVar("x");
        tag.setScope("bogus");
        tag.setValue("v");
        assertThrows(JspTagException.class, () -> TagHarness.run(tag, TestPageContext.create(), Body.EMPTY));
    }

    @Test
    void deferredExpressionGoesToTheVariableMapper() throws Exception {
        TestPageContext pc = TestPageContext.create();
        ValueExpression expr = new FixedExpression();
        SetTag tag = new SetTag();
        tag.setVar("x");
        tag.setValue(expr);
        TagHarness.run(tag, pc, Body.EMPTY);
        assertSame(expr, pc.getELContext().getVariableMapper().resolveVariable("x"));
        assertNull(pc.findAttribute("x"));
    }

    @Test
    void sameInstanceReusedDoesNotLeakBodyState() throws Exception {
        TestPageContext pc = TestPageContext.create();
        SetTag tag = new SetTag();
        tag.setVar("x");
        TagHarness.run(tag, pc, p -> p.getOut().print("first"));
        assertEquals("first", pc.getAttribute("x"));
        TagHarness.run(tag, pc, p -> p.getOut().print("second"));
        assertEquals("second", pc.getAttribute("x"));
        TagHarness.run(tag, pc, Body.EMPTY);
        assertNotNull(pc.getAttribute("x"));
        assertEquals("", pc.getAttribute("x"));
    }
}
