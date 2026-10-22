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

import java.io.StringReader;

import io.vidocq.bodoni.core.testing.Body;
import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestPageContext;

import org.junit.jupiter.api.Test;

class OutTagTest {

    private static String run(OutTag tag, Body body) throws Exception {
        return TagHarness.run(tag, TestPageContext.create(), body);
    }

    @Test
    void escapesXmlByDefault() throws Exception {
        OutTag tag = new OutTag();
        tag.setValue("<b>");
        assertEquals("&lt;b&gt;", run(tag, Body.EMPTY));
    }

    @Test
    void escapeXmlFalseWritesRaw() throws Exception {
        OutTag tag = new OutTag();
        tag.setValue("<b>");
        tag.setEscapeXml(false);
        assertEquals("<b>", run(tag, Body.EMPTY));
    }

    @Test
    void defaultValueIsEscapedToo() throws Exception {
        OutTag tag = new OutTag();
        tag.setValue(null);
        tag.setDefault("<none>");
        assertEquals("&lt;none&gt;", run(tag, Body.EMPTY));
    }

    @Test
    void defaultValueRawWhenEscapingOff() throws Exception {
        OutTag tag = new OutTag();
        tag.setDefault("<none>");
        tag.setEscapeXml(false);
        assertEquals("<none>", run(tag, Body.EMPTY));
    }

    @Test
    void bodyIsTheFallbackAndIsTrimmed() throws Exception {
        OutTag tag = new OutTag();
        assertEquals("fallback", run(tag, pc -> pc.getOut().print("  fallback \n")));
    }

    @Test
    void nothingAtAllWritesNothing() throws Exception {
        assertEquals("", run(new OutTag(), Body.EMPTY));
    }

    @Test
    void readerIsStreamedAndEscaped() throws Exception {
        OutTag tag = new OutTag();
        tag.setValue(new StringReader("a<b"));
        assertEquals("a&lt;b", run(tag, Body.EMPTY));
    }

    @Test
    void bodyIsNotEvaluatedWhenValueIsPresent() throws Exception {
        OutTag tag = new OutTag();
        tag.setValue("v");
        assertEquals("v", run(tag, pc -> pc.getOut().print("body")));
    }

    @Test
    void sameInstanceReusedDoesNotLeak() throws Exception {
        OutTag tag = new OutTag();
        TestPageContext pc = TestPageContext.create();
        tag.setValue(null);
        assertEquals("first", TagHarness.run(tag, pc, p -> p.getOut().print("first")));
        // second use: body is evaluated again, no stale body content
        assertEquals("second", TagHarness.run(tag, pc, p -> p.getOut().print("second")));
        tag.setValue("<x>");
        assertEquals("&lt;x&gt;", TagHarness.run(tag, pc, Body.EMPTY));
        tag.setValue(null);
        assertEquals("", TagHarness.run(tag, pc, Body.EMPTY));
    }

    @Test
    void reusedAsAnEmptyElementDoesNotWriteThePreviousBody() throws Exception {
        // A pooled handler: first <c:out value="${null}">first</c:out>, then
        // <c:out value="${null}"/>, which gets no setBodyContent from the container.
        OutTag tag = new OutTag();
        TestPageContext pc = TestPageContext.create();
        tag.setValue(null);
        assertEquals("first", TagHarness.run(tag, pc, p -> p.getOut().print("first")));
        assertEquals("", TagHarness.run(tag, pc, Body.NONE));
    }
}
