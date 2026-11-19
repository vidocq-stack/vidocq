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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;

import javax.xml.transform.SourceLocator;
import javax.xml.transform.TransformerException;

import io.vidocq.bodoni.core.internal.SystemIdPolicy;
import io.vidocq.bodoni.core.testing.TestPageContext;

import jakarta.servlet.jsp.JspException;

import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/** {@link XmlSupport#documentFailure}: the message and the causes of a failed document. */
class XmlSupportTest {

    private static final String ACTION = "x:parse";
    private static final String REFUSED =
            "x:parse: external reference 'e.xml' refused, system id '(none)': no system id";

    private final TestPageContext pc = TestPageContext.create();

    private SystemIdPolicy refusing() {
        SystemIdPolicy policy = SystemIdPolicy.of(ACTION, null, pc);
        assertThrows(SAXException.class, () -> policy.resolveEntity(null, null, null, "e.xml"));
        return policy;
    }

    @Test
    void withoutARecordedFailureTheCauseNamesTheFailure() {
        IOException cause = new IOException("broken");

        JspException e = XmlSupport.documentFailure(ACTION, cause, SystemIdPolicy.of(ACTION, null, pc));

        assertEquals("x:parse: broken, system id '(none)'", e.getMessage());
        assertSame(cause, e.getCause());
    }

    @Test
    void aRecordedFailureReachedByTheCauseGivesTheMessage() {
        SystemIdPolicy policy = refusing();
        SAXException cause = new SAXException("mangled", policy.recordedFailure());

        JspException e = XmlSupport.documentFailure(ACTION, cause, SystemIdPolicy.of(ACTION, null, pc), policy);

        assertEquals(REFUSED, e.getMessage());
        assertSame(cause, e.getCause());
        assertArrayEquals(new Throwable[0], e.getSuppressed());
    }

    @Test
    void aRecordedFailureTheCauseDoesNotReachIsSuppressed() {
        SystemIdPolicy policy = refusing();
        IOException cause = new IOException("bodoni-webapp:e.xml");

        JspException e = XmlSupport.documentFailure(ACTION, cause, policy);

        assertEquals(REFUSED, e.getMessage());
        assertSame(cause, e.getCause());
        assertArrayEquals(new Throwable[] {policy.recordedFailure()}, e.getSuppressed());
    }

    @Test
    void aWrapperIsUnwrappedToTheParseFailureItsSystemIdNamedAsThePageGaveIt() {
        SystemIdPolicy policy = SystemIdPolicy.of(ACTION, "/x/doc.xml", pc);
        SAXParseException parse = new SAXParseException("unclosed", null, "bodoni-webapp:/x/doc.xml", 1, 4);
        TransformerException cause = new TransformerException(parse);

        JspException e = XmlSupport.documentFailure(ACTION, cause, policy);

        assertEquals("x:parse: unclosed, line 1, column 4, system id '/x/doc.xml'", e.getMessage());
        assertSame(cause, e.getCause());
    }

    @Test
    void theLocatedSystemIdWinsOverThePolicyOne() {
        SystemIdPolicy policy = SystemIdPolicy.of(ACTION, "/t/s.xsl", pc);
        SAXParseException parse = new SAXParseException("bad", null, "bodoni-webapp:/t/inc.xsl", 1, 1);

        JspException e = XmlSupport.documentFailure(ACTION, new TransformerException("wrapped", parse), policy);

        assertEquals("x:parse: wrapped, line 1, column 1, system id '/t/inc.xsl'", e.getMessage());
    }

    @Test
    void aParseFailureOutsideTheChainGivesTheLocation() {
        SystemIdPolicy policy = SystemIdPolicy.of(ACTION, "/x/doc.xml", pc);
        SAXParseException parse = new SAXParseException("unclosed", null, "bodoni-webapp:/x/doc.xml", 1, 9);
        TransformerException cause = new TransformerException(new IllegalStateException("unclosed"));

        JspException e = XmlSupport.documentFailure(ACTION, cause, parse, policy);

        assertEquals("x:parse: unclosed, line 1, column 9, system id '/x/doc.xml'", e.getMessage());
        assertSame(cause, e.getCause());
    }

    @Test
    void anXsltLocationGivesTheLineAndColumn() {
        SystemIdPolicy policy = SystemIdPolicy.of(ACTION, "/t/s.xsl", pc);
        SourceLocator locator = new Locator("bodoni-webapp:/t/s.xsl", 3, 7);

        JspException e = XmlSupport.documentFailure(ACTION, new TransformerException("bad select", locator), policy);

        assertEquals("x:parse: bad select, line 3, column 7, system id '/t/s.xsl'", e.getMessage());
    }

    @Test
    void anUnknownLineOrColumnIsLeftOut() {
        SystemIdPolicy policy = SystemIdPolicy.of(ACTION, "/t/s.xsl", pc);
        TransformerException noLine = new TransformerException("no line", new Locator(null, -1, -1));
        TransformerException noColumn = new TransformerException("no column", new Locator(null, 2, -1));

        assertEquals("x:parse: no line, system id '/t/s.xsl'",
                XmlSupport.documentFailure(ACTION, noLine, policy).getMessage());
        assertEquals("x:parse: no column, line 2, system id '/t/s.xsl'",
                XmlSupport.documentFailure(ACTION, noColumn, policy).getMessage());
    }

    /** A fixed XSLT location. */
    private record Locator(String systemId, int line, int column) implements SourceLocator {

        @Override
        public String getPublicId() {
            return null;
        }

        @Override
        public String getSystemId() {
            return systemId;
        }

        @Override
        public int getLineNumber() {
            return line;
        }

        @Override
        public int getColumnNumber() {
            return column;
        }
    }

    @Test
    void thePrivateBasesInAMessageAreMappedBackToWhatThePageWrote() {
        SystemIdPolicy none = SystemIdPolicy.of(ACTION, null, pc);
        SystemIdPolicy relative = SystemIdPolicy.of(ACTION, "/t/s.xsl", pc);
        IOException cause = new IOException(
                "bodoni-none:/doc/: line 1: bad; see bodoni-webapp:/t/s.xsl, bodoni-webapp:/t/inc.xsl and bodoni-none:/doc/e.xml");

        JspException e = XmlSupport.documentFailure(ACTION, cause, none, relative);

        assertEquals("x:parse: (none): line 1: bad; see /t/s.xsl, /t/inc.xsl and e.xml, system id '(none)'",
                e.getMessage());
    }
}
