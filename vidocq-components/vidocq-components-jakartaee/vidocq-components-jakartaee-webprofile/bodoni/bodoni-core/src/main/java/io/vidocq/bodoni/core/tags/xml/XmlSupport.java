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

/** What the {@code x:} handlers share: the XPath context, the scopes and the failures. */
final class XmlSupport {

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

    /** A {@code select} expression that cannot be evaluated, its cause kept. */
    static JspTagException xpathFailure(String action, String select, Exception cause) {
        return new JspTagException(action + ": cannot evaluate '" + select + "': " + cause.getMessage(), cause);
    }

    /**
     * A document that cannot be parsed or transformed. When a policy recorded a refused or
     * unreadable external reference, the message is that failure's (the JDK may wrap or
     * rewrite it on the way); the cause is always {@code cause}, and a recorded failure that
     * the cause chain does not reach is added as suppressed.
     *
     * @param action   the action name
     * @param cause    the parse or transform failure
     * @param policies the policies of the documents involved, the first recorded failure wins
     */
    static JspException documentFailure(String action, Exception cause, SystemIdPolicy... policies) {
        for (SystemIdPolicy policy : policies) {
            JspException recorded = policy.recordedFailure();
            if (recorded != null) {
                JspException failure = new JspException(recorded.getMessage(), cause);
                if (!reaches(cause, recorded)) {
                    failure.addSuppressed(recorded);
                }
                return failure;
            }
        }
        return new JspException(action + ": " + cause.getMessage(), cause);
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
