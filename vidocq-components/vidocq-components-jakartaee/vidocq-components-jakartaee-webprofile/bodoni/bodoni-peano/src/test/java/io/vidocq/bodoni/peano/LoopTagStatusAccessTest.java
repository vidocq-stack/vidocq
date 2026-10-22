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
package io.vidocq.bodoni.peano;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import jakarta.el.ELException;
import jakarta.el.ExpressionFactory;
import jakarta.el.PropertyNotWritableException;
import jakarta.el.StandardELContext;
import jakarta.el.ValueExpression;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.jstl.core.LoopTagStatus;
import jakarta.servlet.jsp.jstl.core.LoopTagSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code varStatus} under reflection denial (BUG.md BOD-001, Tags 3.0 section 6.1): the status
 * object is an anonymous class, so only the table of this module, reached by supertype lookup
 * on {@link LoopTagStatus}, can serve it.
 */
class LoopTagStatusAccessTest {
    private static final String POLICY = "peano.reflection";
    private static final String[] PROPERTIES = {"current", "index", "count", "first", "last", "begin", "end", "step"};
    private final ExpressionFactory factory = ExpressionFactory.newInstance();
    private final StandardELContext context = new StandardELContext(factory);
    private String previous;

    @BeforeEach
    void deny() {
        previous = System.getProperty(POLICY);
        System.setProperty(POLICY, "deny");
    }

    @AfterEach
    void restore() {
        if (previous == null) {
            System.clearProperty(POLICY);
        } else {
            System.setProperty(POLICY, previous);
        }
    }

    private ValueExpression expression(Object status, String text) {
        context.getVariableMapper().setVariable("s", factory.createValueExpression(status, Object.class));
        return factory.createValueExpression(context, text, Object.class);
    }

    private Object eval(Object status, String text) {
        return expression(status, text).getValue(context);
    }

    private static LoopTagStatus status(Object current, int index, int count, Integer begin, Integer end,
            Integer step) {
        return new LoopTagStatus() {
            @Override
            public Object getCurrent() {
                return current;
            }

            @Override
            public int getIndex() {
                return index;
            }

            @Override
            public int getCount() {
                return count;
            }

            @Override
            public boolean isFirst() {
                return count == 1;
            }

            @Override
            public boolean isLast() {
                return index == 2;
            }

            @Override
            public Integer getBegin() {
                return begin;
            }

            @Override
            public Integer getEnd() {
                return end;
            }

            @Override
            public Integer getStep() {
                return step;
            }
        };
    }

    @Test
    void readsEveryPropertyOfAnAnonymousStatus() {
        var status = status("b", 1, 2, 0, 5, 2);
        assertEquals("b", eval(status, "${s.current}"));
        assertEquals(1, eval(status, "${s.index}"));
        assertEquals(2, eval(status, "${s.count}"));
        assertEquals(false, eval(status, "${s.first}"));
        assertEquals(false, eval(status, "${s.last}"));
        assertEquals(0, eval(status, "${s.begin}"));
        assertEquals(5, eval(status, "${s.end}"));
        assertEquals(2, eval(status, "${s.step}"));
        assertEquals("b:1:2", eval(status, "${s.current}:${s.index}:${s.count}"));
        assertEquals(1, eval(status, "${s['index']}"));
    }

    @Test
    void readsFirstAndLast() {
        assertEquals(true, eval(status("a", 0, 1, null, null, null), "${s.first}"));
        assertEquals(true, eval(status("c", 2, 3, null, null, null), "${s.last}"));
    }

    @Test
    void unspecifiedBoundsAreNull() {
        var status = status("a", 0, 1, null, null, null);
        assertNull(eval(status, "${s.begin}"));
        assertNull(eval(status, "${s.end}"));
        assertNull(eval(status, "${s.step}"));
    }

    @Test
    void invokesTheStatusAccessorMethods() {
        var status = status("b", 1, 2, 0, 5, 2);
        assertEquals("b", eval(status, "${s.getCurrent()}"));
        assertEquals(1, eval(status, "${s.getIndex()}"));
        assertEquals(2, eval(status, "${s.getCount()}"));
        assertEquals(false, eval(status, "${s.isFirst()}"));
        assertEquals(false, eval(status, "${s.isLast()}"));
        assertEquals(0, eval(status, "${s.getBegin()}"));
        assertEquals(5, eval(status, "${s.getEnd()}"));
        assertEquals(2, eval(status, "${s.getStep()}"));
    }

    @Test
    void everyPropertyIsReadOnly() {
        var status = status("b", 1, 2, 0, 5, 2);
        for (String property : PROPERTIES) {
            var value = expression(status, "${s." + property + "}");
            assertTrue(value.isReadOnly(context), property);
            assertNull(value.getType(context), property);
            assertThrows(PropertyNotWritableException.class, () -> value.setValue(context, 1), property);
        }
    }

    @Test
    void readsTheStatusOfARealLoopTag() throws JspTagException {
        var tag = new CountingTag();
        tag.bounds(1, 3, 2);
        var status = tag.getLoopStatus();
        assertEquals(1, eval(status, "${s.begin}"));
        assertEquals(3, eval(status, "${s.end}"));
        assertEquals(2, eval(status, "${s.step}"));
        assertEquals(status.getCount(), eval(status, "${s.count}"));
        assertEquals(status.isFirst(), eval(status, "${s.first}"));
        assertEquals(status.isLast(), eval(status, "${s.last}"));
    }

    @Test
    void unknownPropertiesStillHitTheDenyGuard() {
        var status = status("b", 1, 2, 0, 5, 2);
        var failure = assertThrows(ELException.class, () -> eval(status, "${s.other}"));
        assertEquals("Reflection fallback denied for " + status.getClass().getName()
                + "; register a generated accessor provider or a custom resolver", failure.getMessage());
    }

    @Test
    void theProviderIsDeclaredByTheJavaModule() {
        var module = ModuleLayer.boot().findModule("io.vidocq.bodoni.peano").orElseThrow();
        var provides = module.getDescriptor().provides().stream()
                .filter(p -> p.service().equals("io.vidocq.peano.spi.AccessorProvider"))
                .findFirst().orElseThrow();
        assertEquals(List.of("io.vidocq.bodoni.peano.internal.BodoniAccessorProvider"), provides.providers());
        assertTrue(module.getDescriptor().exports().isEmpty());
    }

    /** A minimal loop tag, to obtain the status object of the JSTL API itself. */
    private static final class CountingTag extends LoopTagSupport {
        private static final long serialVersionUID = 1L;

        void bounds(int from, int to, int by) {
            begin = from;
            end = to;
            step = by;
            beginSpecified = true;
            endSpecified = true;
            stepSpecified = true;
        }

        @Override
        protected Object next() {
            return "x";
        }

        @Override
        protected boolean hasNext() {
            return false;
        }

        @Override
        protected void prepare() {
            // Nothing to prepare: the test only reads the status object.
        }
    }
}
