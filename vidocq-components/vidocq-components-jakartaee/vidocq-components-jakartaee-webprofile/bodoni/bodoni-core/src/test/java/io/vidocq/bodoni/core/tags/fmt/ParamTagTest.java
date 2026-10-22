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

package io.vidocq.bodoni.core.tags.fmt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Locale;

import io.vidocq.bodoni.core.testing.Body;
import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestHttpServletRequest;
import io.vidocq.bodoni.core.testing.TestPageContext;

import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.PageContext;
import jakarta.servlet.jsp.jstl.core.Config;

import org.junit.jupiter.api.Test;

class ParamTagTest {

    private final TestPageContext pc = TestPageContext.create();

    private MessageTag athletes() {
        ((TestHttpServletRequest) pc.getRequest()).setLocales(Locale.ENGLISH);
        Config.set(pc, Config.FMT_LOCALIZATION_CONTEXT, "test-bundles.msg.Messages", PageContext.PAGE_SCOPE);
        MessageTag tag = new MessageTag();
        tag.setKey("athletes");
        return tag;
    }

    @Test
    void valueComesFromTheTrimmedBodyWithoutValueAttribute() throws Exception {
        MessageTag tag = athletes();
        String out = TagHarness.run(tag, pc, p -> TagHarness.run(new ParamTag(), pc, tag,
                q -> q.getOut().print("  many \n")));
        assertEquals("There are many athletes registered.", out);
    }

    @Test
    void valueAttributeBeatsTheBody() throws Exception {
        MessageTag tag = athletes();
        ParamTag param = new ParamTag();
        param.setValue(5);
        assertEquals("There are 5 athletes registered.", TagHarness.run(tag, pc,
                p -> TagHarness.run(param, pc, tag, q -> q.getOut().print("ignored"))));
    }

    @Test
    void reusedInstanceDoesNotKeepTheValueOfThePreviousUse() throws Exception {
        MessageTag tag = athletes();
        ParamTag param = new ParamTag();
        param.setValue(5);
        TagHarness.run(tag, pc, p -> TagHarness.run(param, pc, tag, Body.NONE));
        param.release();
        assertEquals("There are body athletes registered.", TagHarness.run(tag, pc,
                p -> TagHarness.run(param, pc, tag, q -> q.getOut().print("body"))));
    }

    @Test
    void outsideMessageIsAJspTagException() {
        ParamTag param = new ParamTag();
        param.setValue(1);
        assertThrows(JspTagException.class, () -> TagHarness.run(param, pc, Body.NONE));
    }
}
