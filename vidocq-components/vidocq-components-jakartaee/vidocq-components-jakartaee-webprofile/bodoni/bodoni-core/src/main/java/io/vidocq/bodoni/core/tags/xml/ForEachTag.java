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

import java.util.Collections;
import java.util.Iterator;

import javax.xml.xpath.XPathExpressionException;

import io.vidocq.bodoni.core.internal.XPathEngine;
import io.vidocq.bodoni.core.tags.core.RangeLoopTagSupport;

import jakarta.servlet.jsp.JspTagException;

import org.w3c.dom.Node;

/**
 * {@code <x:forEach>} (Tags 3.0 section 12.6): evaluates the XPath expression {@code select},
 * which must yield a node-set, and repeats its body over the selected nodes in document
 * order. The current {@link Node} is exported in {@code var} and is the context node of the
 * XPath expressions nested in the body. {@code begin}, {@code end}, {@code step} and
 * {@code varStatus} are handled by {@link RangeLoopTagSupport}.
 */
public class ForEachTag extends RangeLoopTagSupport {

    private static final long serialVersionUID = 1L;

    private static final String ACTION = "x:forEach";

    private String select;
    private transient Iterator<Node> nodes;

    /** @param select the XPath expression selecting the nodes to iterate over */
    public void setSelect(String select) {
        this.select = select;
    }

    @Override
    protected void prepare() throws JspTagException {
        if (select == null || select.isEmpty()) {
            // section 12.6, Null & Error Handling
            throw new JspTagException(ACTION + ": select is empty");
        }
        if (!prepareRange()) {
            nodes = Collections.emptyIterator();
            return;
        }
        try {
            nodes = XPathEngine.nodes(pageContext, XmlSupport.contextNode(this), select).iterator();
        } catch (XPathExpressionException e) {
            throw XmlSupport.xpathFailure(ACTION, select, e);
        }
    }

    @Override
    protected boolean hasNext() throws JspTagException {
        return nodes.hasNext();
    }

    @Override
    protected Object next() throws JspTagException {
        return nodes.next();
    }

    /**
     * {@inheritDoc} The selected nodes are dropped too: a pooled handler must not keep the
     * document alive between invocations.
     */
    @Override
    public void doFinally() {
        super.doFinally();
        nodes = null;
    }

    @Override
    public void release() {
        super.release();
        select = null;
        nodes = null;
    }
}
