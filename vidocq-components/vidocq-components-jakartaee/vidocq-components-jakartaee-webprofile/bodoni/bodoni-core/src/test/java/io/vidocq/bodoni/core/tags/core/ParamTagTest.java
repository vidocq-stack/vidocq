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

import io.vidocq.bodoni.core.testing.Body;
import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestPageContext;

import jakarta.servlet.jsp.JspTagException;

import org.junit.jupiter.api.Test;

class ParamTagTest {

    private static String url(Body inner) throws Exception {
        TestPageContext pc = TestPageContext.create();
        UrlTag tag = new UrlTag();
        tag.setValue("p");
        tag.setVar("u");
        TagHarness.run(tag, pc, inner == null ? Body.EMPTY : p -> inner.render(p));
        return (String) pc.getAttribute("u");
    }

    @Test
    void valueFromBodyWhenNoValueAttribute() throws Exception {
        TestPageContext pc = TestPageContext.create();
        UrlTag parent = new UrlTag();
        parent.setValue("p");
        parent.setVar("u");
        TagHarness.run(parent, pc, p -> {
            ParamTag param = new ParamTag();
            param.setName("n");
            TagHarness.run(param, pc, parent, q -> q.getOut().print("  v 1 "));
        });
        assertEquals("p?n=v+1;jsessionid=T", pc.getAttribute("u"));
    }

    @Test
    void nullValueIsEmptyAndEmptyNameIsIgnored() throws Exception {
        TestPageContext pc = TestPageContext.create();
        UrlTag parent = new UrlTag();
        parent.setValue("p");
        parent.setVar("u");
        TagHarness.run(parent, pc, p -> {
            UrlTagTest.addParam(p, parent, "n", null);
            UrlTagTest.addParam(p, parent, "", "x");
            UrlTagTest.addParam(p, parent, null, "x");
        });
        assertEquals("p?n=;jsessionid=T", pc.getAttribute("u"));
    }

    @Test
    void outsideAParamParentIsAnError() {
        ParamTag param = new ParamTag();
        param.setName("n");
        assertThrows(JspTagException.class, () -> TagHarness.run(param, TestPageContext.create(), Body.EMPTY));
    }

    @Test
    void reusedInstanceForgetsPreviousValue() throws Exception {
        TestPageContext pc = TestPageContext.create();
        UrlTag parent = new UrlTag();
        parent.setValue("p");
        parent.setVar("u");
        ParamTag param = new ParamTag();
        param.setName("n");
        TagHarness.run(parent, pc, p -> TagHarness.run(param, pc, parent, q -> q.getOut().print("one")));
        param.release();
        param.setName("n");
        TagHarness.run(parent, pc, p -> TagHarness.run(param, pc, parent, Body.EMPTY));
        assertEquals("p?n=;jsessionid=T", pc.getAttribute("u"));
    }
}
