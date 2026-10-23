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

import java.util.Objects;

import javax.xml.transform.SourceLocator;
import javax.xml.transform.TransformerException;
import javax.xml.xpath.XPathExpressionException;

import io.vidocq.bodoni.core.internal.Scopes;
import io.vidocq.bodoni.core.internal.SystemIdPolicy;
import io.vidocq.bodoni.core.internal.XPathEngine;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.PageContext;
import jakarta.servlet.jsp.tagext.Tag;
import jakarta.servlet.jsp.tagext.TagSupport;

import org.w3c.dom.Node;
import org.xml.sax.SAXParseException;

/** What the {@code x:} handlers share: the XPath context, the scopes and the failures. */
final class XmlSupport {

    /** Message of a failed document: action, cause message, line and column (when known), system id. */
    private static final String DOCUMENT_FAILURE = "%s: %s%s, system id '%s'";

    /** Bound of a cause-chain walk, against cyclic chains. */
    private static final int MAX_DEPTH = 32;

    private XmlSupport() {
    }

    /**
     * The context node of an XPath expression evaluated by {@code tag}: the current node of
     * the nearest enclosing {@code <x:forEach>} (Tags 3.0 section 12.6).
     *
     * @param tag the handler evaluating the expression
     * @return that node, or {@code null} outside any {@code <x:forEach>}: the empty document
     *         of section 11.1.6 is then the context
     */
    static Node contextNode(Tag tag) {
        if (TagSupport.findAncestorWithClass(tag, ForEachTag.class) instanceof ForEachTag loop) {
            return (Node) loop.getCurrent();
        }
        return null;
    }

    /**
     * The test of {@code <x:if>} and {@code <x:when>}: {@code select} converted by the XPath
     * {@code boolean()} function (Tags 3.0 section 12.1).
     *
     * @throws JspTagException naming the action, when {@code select} cannot be evaluated
     */
    static boolean test(String action, Tag tag, PageContext pc, String select) throws JspTagException {
        try {
            return XPathEngine.bool(pc, contextNode(tag), select);
        } catch (XPathExpressionException e) {
            throw xpathFailure(action, select, e);
        }
    }

    /**
     * The {@link jakarta.servlet.jsp.PageContext} constant of a {@code scope} attribute.
     *
     * @throws JspTagException naming the action, for an unknown scope name
     */
    static int scopeId(String action, String scope) throws JspTagException {
        try {
            return Scopes.of(scope);
        } catch (IllegalArgumentException e) {
            throw new JspTagException(action + ": " + e.getMessage(), e);
        }
    }

    /**
     * The resolution policy of a system id attribute.
     *
     * @throws JspTagException with the policy's message, when {@code systemId} is not a URI
     *                         or leaves the context root
     */
    static SystemIdPolicy policy(String action, String systemId, PageContext pc) throws JspTagException {
        try {
            return SystemIdPolicy.of(action, systemId, pc);
        } catch (IllegalArgumentException e) {
            throw new JspTagException(e.getMessage(), e);
        }
    }

    /** A {@code select} expression that cannot be evaluated, its cause kept. */
    static JspTagException xpathFailure(String action, String select, Exception cause) {
        return new JspTagException(action + ": cannot evaluate '" + select + "': " + cause.getMessage(), cause);
    }

    /**
     * A document that cannot be parsed or transformed. When a policy recorded a refused or
     * unreadable external reference, the message is that failure's (the JDK may wrap or
     * rewrite it on the way). Otherwise it is the message of the innermost meaningful cause
     * (wrappers that only repeat their cause are skipped), the line and column of the innermost
     * SAX or XSLT location that knows them, and the system id of the failing document: the
     * innermost one a SAX or XSLT location names, else the first policy's. Either way the private bases given to the JDK are mapped back to what the page
     * wrote ({@link SystemIdPolicy#pageView}). The cause is always {@code cause}, and a
     * recorded failure that the cause chain does not reach is added as suppressed.
     *
     * @param action   the action name
     * @param cause    the parse or transform failure
     * @param policies the policies of the documents involved, at least one; the first recorded
     *                 failure wins, and the first is the failing document's when no location
     *                 names it
     */
    static JspException documentFailure(String action, Exception cause, SystemIdPolicy... policies) {
        for (SystemIdPolicy policy : policies) {
            JspException recorded = policy.recordedFailure();
            if (recorded != null) {
                JspException failure = new JspException(SystemIdPolicy.pageView(recorded.getMessage(), policies), cause);
                if (!reaches(cause, recorded)) {
                    failure.addSuppressed(recorded);
                }
                return failure;
            }
        }
        Throwable reason = innermost(cause);
        String text = Objects.toString(reason.getMessage(), reason.getClass().getName());
        Location location = located(cause);
        String systemId = Objects.requireNonNullElse(location.systemId(), policies[0].parserSystemId());
        String message = DOCUMENT_FAILURE.formatted(action, text, location.position(), systemId);
        return new JspException(SystemIdPolicy.pageView(message, policies), cause);
    }

    /** The first cause that is not a mere wrapper repeating the message of its own cause. */
    private static Throwable innermost(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; depth < MAX_DEPTH && current.getCause() != null && isWrapper(current); depth++) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean isWrapper(Throwable t) {
        String message = t.getMessage();
        Throwable cause = t.getCause();
        return message == null || message.equals(cause.toString()) || message.equals(cause.getMessage());
    }

    /**
     * The SAX or XSLT locations of the chain: the innermost system id, and the innermost known
     * line with its column; {@code null} and {@code -1} for none.
     */
    private static Location located(Throwable failure) {
        Location located = Location.NONE;
        Throwable t = failure;
        for (int depth = 0; depth < MAX_DEPTH && t != null; depth++, t = t.getCause()) {
            Location here = locationOf(t);
            if (here != null) {
                located = located.refinedBy(here);
            }
        }
        return located;
    }

    /** The location a SAX or XSLT failure carries, {@code null} for any other throwable. */
    private static Location locationOf(Throwable t) {
        if (t instanceof SAXParseException parse) {
            return new Location(parse.getSystemId(), parse.getLineNumber(), parse.getColumnNumber());
        }
        if (t instanceof TransformerException transform && transform.getLocator() != null) {
            SourceLocator locator = transform.getLocator();
            return new Location(locator.getSystemId(), locator.getLineNumber(), locator.getColumnNumber());
        }
        return null;
    }

    /** Where a document failed; a line or column below 1 is unknown. */
    private record Location(String systemId, int line, int column) {

        static final Location NONE = new Location(null, -1, -1);

        /** This location with the system id and the line (with its column) {@code inner} knows. */
        Location refinedBy(Location inner) {
            String id = inner.systemId() != null ? inner.systemId() : systemId;
            return inner.line() > 0 ? new Location(id, inner.line(), inner.column()) : new Location(id, line, column);
        }

        /** {@code ", line <n>, column <m>"}, without the unknown parts. */
        String position() {
            if (line < 1) {
                return "";
            }
            return column < 1 ? ", line " + line : ", line " + line + ", column " + column;
        }
    }

    private static boolean reaches(Throwable chain, Throwable target) {
        for (Throwable t = chain; t != null; t = t.getCause()) {
            if (t == target) {
                return true;
            }
        }
        return false;
    }
}
