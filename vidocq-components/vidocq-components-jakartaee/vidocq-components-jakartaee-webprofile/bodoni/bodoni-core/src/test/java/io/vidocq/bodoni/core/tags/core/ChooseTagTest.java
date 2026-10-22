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
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestPageContext;

import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.tagext.TagSupport;

import org.junit.jupiter.api.Test;

class ChooseTagTest {

    private static void when(ChooseTag choose, TestPageContext pc, boolean test, String text) throws Exception {
        WhenTag w = new WhenTag();
        w.setTest(test);
        TagHarness.run(w, pc, choose, p -> p.getOut().print(text));
    }

    private static void otherwise(ChooseTag choose, TestPageContext pc, String text) throws Exception {
        TagHarness.run(new OtherwiseTag(), pc, choose, p -> p.getOut().print(text));
    }

    private static String choose(ChooseTag choose, TestPageContext pc, boolean a, boolean b) throws Exception {
        return TagHarness.run(choose, pc, p -> {
            try {
                when(choose, pc, a, "A");
                when(choose, pc, b, "B");
                otherwise(choose, pc, "O");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void onlyTheFirstTrueWhenRenders() throws Exception {
        assertEquals("B", choose(new ChooseTag(), TestPageContext.create(), false, true));
        assertEquals("A", choose(new ChooseTag(), TestPageContext.create(), true, true));
    }

    @Test
    void otherwiseRendersWhenNoWhenMatched() throws Exception {
        assertEquals("O", choose(new ChooseTag(), TestPageContext.create(), false, false));
    }

    @Test
    void reusedChooseResetsItsState() throws Exception {
        ChooseTag c = new ChooseTag();
        assertEquals("A", choose(c, TestPageContext.create(), true, false));
        assertEquals("O", choose(c, TestPageContext.create(), false, false));
    }

    @Test
    void whenOutsideChooseIsRejected() {
        WhenTag w = new WhenTag();
        w.setTest(true);
        assertThrows(JspTagException.class,
                () -> TagHarness.run(w, TestPageContext.create(), new TagSupport(), p -> { }));
        assertThrows(JspTagException.class,
                () -> TagHarness.run(w, TestPageContext.create(), p -> { }));
    }

    @Test
    void otherwiseOutsideChooseIsRejected() {
        assertThrows(JspTagException.class,
                () -> TagHarness.run(new OtherwiseTag(), TestPageContext.create(), new TagSupport(), p -> { }));
        assertThrows(JspTagException.class,
                () -> TagHarness.run(new OtherwiseTag(), TestPageContext.create(), p -> { }));
    }
}
