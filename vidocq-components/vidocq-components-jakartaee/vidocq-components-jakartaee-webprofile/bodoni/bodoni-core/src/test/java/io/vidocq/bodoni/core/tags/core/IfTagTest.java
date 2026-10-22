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

import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestPageContext;

import jakarta.servlet.jsp.PageContext;

import org.junit.jupiter.api.Test;

class IfTagTest {

    @Test
    void trueRendersTheBody() throws Exception {
        IfTag tag = new IfTag();
        tag.setTest(true);
        assertEquals("body", TagHarness.run(tag, TestPageContext.create(), p -> p.getOut().print("body")));
    }

    @Test
    void falseSkipsTheBody() throws Exception {
        IfTag tag = new IfTag();
        tag.setTest(false);
        assertEquals("", TagHarness.run(tag, TestPageContext.create(), p -> p.getOut().print("body")));
    }

    @Test
    void exportsTheConditionInTheRequestedScope() throws Exception {
        TestPageContext pc = TestPageContext.create();
        IfTag tag = new IfTag();
        tag.setTest(true);
        tag.setVar("r");
        tag.setScope("request");
        TagHarness.run(tag, pc, p -> { });
        assertEquals(Boolean.TRUE, pc.getAttribute("r", PageContext.REQUEST_SCOPE));
    }

    @Test
    void reusedInstanceFollowsTheNewAttributes() throws Exception {
        TestPageContext pc = TestPageContext.create();
        IfTag tag = new IfTag();
        tag.setTest(true);
        assertEquals("x", TagHarness.run(tag, pc, p -> p.getOut().print("x")));
        tag.setTest(false);
        tag.setVar("r");
        assertEquals("", TagHarness.run(tag, pc, p -> p.getOut().print("x")));
        assertEquals(Boolean.FALSE, pc.getAttribute("r", PageContext.PAGE_SCOPE));
    }
}
