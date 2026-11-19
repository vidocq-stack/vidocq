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

package io.vidocq.bodoni.core.internal;

import java.util.List;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathEvaluationResult;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathNodes;

import jakarta.servlet.jsp.PageContext;

import org.w3c.dom.Node;

/**
 * Evaluates the {@code select} XPath expressions of the {@code x:} actions (Tags 3.0
 * section 11.1) over a page: variables per section 11.1.2 ({@link PageVariableResolver}),
 * prefixes per {@link XPathScopes}, the core function library only.
 *
 * <p>Every method evaluates against {@code context}, or the root of an empty document when
 * {@code context} is {@code null} (section 11.1.6), and throws
 * {@link XPathExpressionException} for a {@code null} or empty {@code select}, a malformed
 * expression or an unbound variable. Each call creates its own {@link XPath}: JAXP objects
 * are not thread-safe and none is shared.
 */
public final class XPathEngine {

    private XPathEngine() {
    }

    /**
     * Evaluates {@code select} and maps the result per section 11.1.4.
     *
     * @param pc      the page whose scopes the variables read
     * @param context the context node, or {@code null} for the default one
     * @param select  the XPath expression
     * @return a {@code Boolean}, a {@code Double}, a {@code String} or a {@link NodeSet}
     * @throws XPathExpressionException if the expression cannot be evaluated
     */
    public static Object select(PageContext pc, Node context, String select) throws XPathExpressionException {
        XPathEvaluationResult<?> result = xpath(pc, select).evaluateExpression(select, contextOf(context));
        Object value = result.value();
        // With type ANY the JDK reports BOOLEAN, NUMBER, STRING or NODESET (a path is a
        // NODESET, never a NODE): every other result is a node-set.
        return switch (result.type()) {
            case BOOLEAN, STRING -> value;
            case NUMBER -> ((Number) value).doubleValue();
            default -> NodeSet.of((XPathNodes) value);
        };
    }

    /**
     * Evaluates {@code select} converted by the XPath {@code string()} function.
     *
     * @param pc      the page whose scopes the variables read
     * @param context the context node, or {@code null} for the default one
     * @param select  the XPath expression
     * @return the string value
     * @throws XPathExpressionException if the expression cannot be evaluated
     */
    public static String string(PageContext pc, Node context, String select) throws XPathExpressionException {
        return xpath(pc, select).evaluate(select, contextOf(context));
    }

    /**
     * Evaluates {@code select} converted by the XPath {@code boolean()} function.
     *
     * @param pc      the page whose scopes the variables read
     * @param context the context node, or {@code null} for the default one
     * @param select  the XPath expression
     * @return the boolean value
     * @throws XPathExpressionException if the expression cannot be evaluated
     */
    public static boolean bool(PageContext pc, Node context, String select) throws XPathExpressionException {
        return (Boolean) xpath(pc, select).evaluate(select, contextOf(context), XPathConstants.BOOLEAN);
    }

    /**
     * Evaluates {@code select}, which must yield a node-set.
     *
     * @param pc      the page whose scopes the variables read
     * @param context the context node, or {@code null} for the default one
     * @param select  the XPath expression
     * @return the selected nodes in document order, unmodifiable
     * @throws XPathExpressionException if the expression cannot be evaluated or its result
     *                                  is not a node-set
     */
    public static List<Node> nodes(PageContext pc, Node context, String select) throws XPathExpressionException {
        if (select(pc, context, select) instanceof NodeSet nodes) {
            return nodes.nodes();
        }
        throw new XPathExpressionException("Not a node-set: " + select);
    }

    private static XPath xpath(PageContext pc, String select) throws XPathExpressionException {
        if (select == null || select.isEmpty()) {
            throw new XPathExpressionException("Missing XPath expression");
        }
        return XmlFactories.xpath(new XPathScopes(),new PageVariableResolver(pc));
    }

    private static Node contextOf(Node context) {
        return context == null ? XmlFactories.emptyDocument() : context;
    }
}
