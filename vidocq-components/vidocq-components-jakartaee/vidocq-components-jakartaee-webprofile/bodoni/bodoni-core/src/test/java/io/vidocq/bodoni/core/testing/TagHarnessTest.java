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

package io.vidocq.bodoni.core.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.tagext.BodyContent;
import jakarta.servlet.jsp.tagext.BodyTagSupport;
import jakarta.servlet.jsp.tagext.Tag;
import jakarta.servlet.jsp.tagext.TagSupport;
import jakarta.servlet.jsp.tagext.TryCatchFinally;

import org.junit.jupiter.api.Test;

class TagHarnessTest {

    /** Repeats its body twice. */
    static final class Twice extends TagSupport {
        private int count;

        @Override
        public int doStartTag() {
            count = 0;
            return EVAL_BODY_INCLUDE;
        }

        @Override
        public int doAfterBody() {
            return ++count < 2 ? EVAL_BODY_AGAIN : SKIP_BODY;
        }
    }

    /** Upper-cases its buffered body. */
    static final class Upper extends BodyTagSupport {
        @Override
        public int doStartTag() {
            return EVAL_BODY_BUFFERED;
        }

        @Override
        public int doAfterBody() throws JspException {
            try {
                BodyContent bc = getBodyContent();
                bc.getEnclosingWriter().write(bc.getString().toUpperCase());
            } catch (java.io.IOException e) {
                throw new JspException(e);
            }
            return SKIP_BODY;
        }
    }

    /** Records every lifecycle call. */
    static final class Recorder extends TagSupport implements TryCatchFinally {
        final List<String> calls = new ArrayList<>();

        jakarta.servlet.jsp.PageContext pc() {
            return pageContext;
        }

        @Override
        public int doStartTag() {
            calls.add("start");
            return EVAL_BODY_INCLUDE;
        }

        @Override
        public int doEndTag() {
            calls.add("end");
            return EVAL_PAGE;
        }

        @Override
        public void doCatch(Throwable t) {
            calls.add("catch:" + t.getMessage());
        }

        @Override
        public void doFinally() {
            calls.add("finally");
        }
    }

    @Test
    void iterationTagRepeatsBody() throws Exception {
        TestPageContext pc = TestPageContext.create();
        assertEquals("xx", TagHarness.run(new Twice(), pc, p -> p.getOut().write("x")));
    }

    @Test
    void bodyTagBuffersBody() throws Exception {
        TestPageContext pc = TestPageContext.create();
        assertEquals("HELLO", TagHarness.run(new Upper(), pc, p -> p.getOut().write("hello")));
    }

    @Test
    void bodyOutputDoesNotLeakWhenBuffered() throws Exception {
        TestPageContext pc = TestPageContext.create();
        // The tag discards the buffered body: nothing reaches the page.
        BodyTagSupport discard = new BodyTagSupport() {
            @Override
            public int doStartTag() {
                return EVAL_BODY_BUFFERED;
            }
        };
        assertEquals("", TagHarness.run(discard, pc, p -> p.getOut().write("hidden")));
    }

    @Test
    void parentAndPageContextAreSet() throws Exception {
        TestPageContext pc = TestPageContext.create();
        Tag parent = new TagSupport();
        Recorder r = new Recorder();
        TagHarness.run(r, pc, parent, Body.EMPTY);
        assertSame(parent, r.getParent());
        assertSame(pc, r.pc());
    }

    @Test
    void tryCatchFinallyRoutesBodyFailure() throws Exception {
        TestPageContext pc = TestPageContext.create();
        Recorder r = new Recorder();
        TagHarness.run(r, pc, p -> {
            throw new IllegalStateException("boom");
        });
        assertEquals(List.of("start", "catch:boom", "finally"), r.calls);
    }

    @Test
    void tryCatchFinallyCompletesNormally() throws Exception {
        TestPageContext pc = TestPageContext.create();
        Recorder r = new Recorder();
        TagHarness.run(r, pc, Body.EMPTY);
        assertEquals(List.of("start", "end", "finally"), r.calls);
    }

    @Test
    void bodyContentIsPoppedWhenBufferedBodyFails() throws Exception {
        TestPageContext pc = TestPageContext.create();
        var before = pc.getOut();
        class Failing extends BodyTagSupport implements TryCatchFinally {
            @Override
            public int doStartTag() {
                return EVAL_BODY_BUFFERED;
            }

            @Override
            public void doCatch(Throwable t) {
            }

            @Override
            public void doFinally() {
            }
        }
        TagHarness.run(new Failing(), pc, p -> {
            throw new IllegalStateException("x");
        });
        assertSame(before, pc.getOut());
    }

    @Test
    void failureWithoutTryCatchFinallyPropagates() {
        TestPageContext pc = TestPageContext.create();
        assertThrows(IllegalStateException.class, () -> TagHarness.run(new Twice(), pc, p -> {
            throw new IllegalStateException("boom");
        }));
    }
}
