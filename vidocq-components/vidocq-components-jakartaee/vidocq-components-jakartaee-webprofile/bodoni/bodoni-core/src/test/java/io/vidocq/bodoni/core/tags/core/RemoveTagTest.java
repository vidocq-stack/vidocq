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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vidocq.bodoni.core.testing.Body;
import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestPageContext;

import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.PageContext;

import org.junit.jupiter.api.Test;

class RemoveTagTest {

    private static TestPageContext populated() {
        TestPageContext pc = TestPageContext.create();
        pc.setAttribute("x", "p", PageContext.PAGE_SCOPE);
        pc.setAttribute("x", "r", PageContext.REQUEST_SCOPE);
        pc.setAttribute("x", "s", PageContext.SESSION_SCOPE);
        pc.setAttribute("x", "a", PageContext.APPLICATION_SCOPE);
        return pc;
    }

    @Test
    void withoutScopeRemovesFromEveryScope() throws Exception {
        TestPageContext pc = populated();
        RemoveTag tag = new RemoveTag();
        tag.setVar("x");
        TagHarness.run(tag, pc, Body.EMPTY);
        assertNull(pc.findAttribute("x"));
    }

    @Test
    void withScopeRemovesFromThatScopeOnly() throws Exception {
        TestPageContext pc = populated();
        RemoveTag tag = new RemoveTag();
        tag.setVar("x");
        tag.setScope("session");
        TagHarness.run(tag, pc, Body.EMPTY);
        assertNull(pc.getAttribute("x", PageContext.SESSION_SCOPE));
        assertEquals("p", pc.getAttribute("x", PageContext.PAGE_SCOPE));
        assertEquals("r", pc.getAttribute("x", PageContext.REQUEST_SCOPE));
        assertEquals("a", pc.getAttribute("x", PageContext.APPLICATION_SCOPE));
    }

    @Test
    void invalidScopeIsRejected() {
        RemoveTag tag = new RemoveTag();
        tag.setVar("x");
        tag.setScope("nope");
        assertThrows(JspTagException.class, () -> TagHarness.run(tag, populated(), Body.EMPTY));
    }

    @Test
    void sameInstanceCanBeReused() throws Exception {
        TestPageContext pc = populated();
        RemoveTag tag = new RemoveTag();
        tag.setVar("x");
        TagHarness.run(tag, pc, Body.EMPTY);
        pc.setAttribute("x", "again");
        TagHarness.run(tag, pc, Body.EMPTY);
        assertNull(pc.findAttribute("x"));
    }
}
