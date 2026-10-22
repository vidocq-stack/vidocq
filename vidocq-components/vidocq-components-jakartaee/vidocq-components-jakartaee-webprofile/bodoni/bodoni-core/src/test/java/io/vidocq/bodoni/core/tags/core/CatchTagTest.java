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

import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestPageContext;

import jakarta.servlet.jsp.PageContext;

import org.junit.jupiter.api.Test;

class CatchTagTest {

    @Test
    void exceptionIsStoredAndNotPropagated() throws Exception {
        TestPageContext pc = TestPageContext.create();
        CatchTag tag = new CatchTag();
        tag.setVar("e");
        String out = TagHarness.run(tag, pc, p -> {
            p.getOut().print("before");
            throw new IllegalStateException("boom");
        });
        assertEquals("before", out);
        Object stored = pc.getAttribute("e", PageContext.PAGE_SCOPE);
        assertInstanceOf(IllegalStateException.class, stored);
        assertEquals("boom", ((Throwable) stored).getMessage());
    }

    @Test
    void noExceptionRemovesTheVariable() throws Exception {
        TestPageContext pc = TestPageContext.create();
        pc.setAttribute("e", "stale");
        CatchTag tag = new CatchTag();
        tag.setVar("e");
        String out = TagHarness.run(tag, pc, p -> p.getOut().print("ok"));
        assertEquals("ok", out);
        assertNull(pc.getAttribute("e", PageContext.PAGE_SCOPE));
    }

    @Test
    void withoutVarTheExceptionIsSwallowed() throws Exception {
        TestPageContext pc = TestPageContext.create();
        CatchTag tag = new CatchTag();
        String out = TagHarness.run(tag, pc, p -> {
            p.getOut().print("x");
            throw new IllegalStateException("boom");
        });
        assertEquals("x", out);
    }

    @Test
    void sameInstanceReusedAfterAThrow() throws Exception {
        TestPageContext pc = TestPageContext.create();
        CatchTag tag = new CatchTag();
        tag.setVar("e");
        TagHarness.run(tag, pc, p -> {
            throw new IllegalStateException("boom");
        });
        assertInstanceOf(IllegalStateException.class, pc.getAttribute("e"));
        TagHarness.run(tag, pc, p -> p.getOut().print("fine"));
        assertNull(pc.getAttribute("e"));
        TagHarness.run(tag, pc, p -> {
            throw new IllegalArgumentException("again");
        });
        assertEquals("again", ((Throwable) pc.getAttribute("e")).getMessage());
    }
}
