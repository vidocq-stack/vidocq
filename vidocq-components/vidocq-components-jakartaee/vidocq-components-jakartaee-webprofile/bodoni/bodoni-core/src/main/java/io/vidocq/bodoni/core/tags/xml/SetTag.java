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

import io.vidocq.bodoni.core.internal.XPathEngine;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.tagext.TagSupport;

/**
 * {@code <x:set>} (Tags 3.0 section 11.4): evaluates the XPath expression {@code select} and
 * stores the result in {@code var} and {@code scope}, mapped to Java as section 11.1.4 says:
 * a {@code Boolean}, a {@code Number}, a {@code String}, or a node-set as an immutable
 * {@link org.w3c.dom.NodeList}.
 */
public class SetTag extends TagSupport {

    private static final long serialVersionUID = 1L;

    private static final String ACTION = "x:set";

    private String select;
    private String varName;
    private String scope;

    /** @param select the XPath expression to evaluate */
    public void setSelect(String select) {
        this.select = select;
    }

    /** @param name the scoped variable receiving the result */
    public void setVar(String name) {
        this.varName = name;
    }

    /** @param scope the scope of {@code var} */
    public void setScope(String scope) {
        this.scope = scope;
    }

    @Override
    public int doEndTag() throws JspException {
        int scopeId = XmlSupport.scopeId(ACTION, scope);
        Object result;
        try {
            result = XPathEngine.select(pageContext, XmlSupport.contextNode(this), select);
        } catch (XPathExpressionException e) {
            throw XmlSupport.xpathFailure(ACTION, select, e);
        }
        pageContext.setAttribute(varName, result, scopeId);
        return EVAL_PAGE;
    }

    @Override
    public void release() {
        super.release();
        select = null;
        varName = null;
        scope = null;
    }
}
