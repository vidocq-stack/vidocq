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
package io.vidocq.bodoni.core.tags.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;

import javax.xml.xpath.XPathExpressionException;

import io.vidocq.bodoni.core.internal.SystemIdPolicy;
import io.vidocq.bodoni.core.internal.XmlDocuments;
import io.vidocq.bodoni.core.testing.Body;
import io.vidocq.bodoni.core.testing.TagHarness;
import io.vidocq.bodoni.core.testing.TestPageContext;

import jakarta.servlet.jsp.JspTagException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code <x:out>} (Tags 3.0 section 11.3). */
class OutTagTest {

    private static final String SPECIALS = "$d/a";

    private final TestPageContext pc = TestPageContext.create();

    @BeforeEach
    void document() throws Exception {
        pc.setAttribute("d", XmlDocuments.parse(
                new StringReader("<a><![CDATA[< > & ' \"]]><b/><b/></a>"), SystemIdPolicy.of("x:parse", null, pc), null));
    }

    private static OutTag out(String select) {
        OutTag tag = new OutTag();
        tag.setSelect(select);
        return tag;
    }

    private String run(OutTag tag) throws Exception {
        return TagHarness.run(tag, pc, Body.NONE);
    }

    @Test
    void escapesXmlByDefault() throws Exception {
        assertEquals("&lt; &gt; &amp; &#039; &#034;", run(out(SPECIALS)));
    }

    @Test
    void escapeXmlTrueEscapes() throws Exception {
        OutTag tag = out(SPECIALS);
        tag.setEscapeXml(true);

        assertEquals("&lt; &gt; &amp; &#039; &#034;", run(tag));
    }

    @Test
    void escapeXmlFalseWritesRaw() throws Exception {
        OutTag tag = out(SPECIALS);
        tag.setEscapeXml(false);

        assertEquals("< > & ' \"", run(tag));
    }

    @Test
    void theResultIsConvertedAsByString() throws Exception {
        assertEquals("2", run(out("count($d//b)")));
        assertEquals("0.5", run(out("1 div 2")));
        assertEquals("true", run(out("boolean($d//b)")));
    }

    @Test
    void theDefaultContextIsAnEmptyDocument() throws Exception {
        assertEquals("0", run(out("count(/*)")));
    }

    @Test
    void aFailingSelectIsATagExceptionKeepingTheCause() {
        OutTag tag = out("$nope");

        JspTagException e = assertThrows(JspTagException.class, () -> run(tag));

        assertTrue(e.getMessage().startsWith("x:out: cannot evaluate '$nope': "), e.getMessage());
        assertInstanceOf(XPathExpressionException.class, e.getCause());
    }

    @Test
    void aReusedInstanceKeepsEscapeXml() throws Exception {
        OutTag tag = out(SPECIALS);
        tag.setEscapeXml(false);

        run(tag);

        assertEquals("< > & ' \"", run(tag));
    }

    @Test
    void releaseRestoresTheEscapingDefault() throws Exception {
        OutTag tag = out(SPECIALS);
        tag.setEscapeXml(false);
        tag.release();
        tag.setSelect(SPECIALS);

        assertEquals("&lt; &gt; &amp; &#039; &#034;", run(tag));
    }
}
