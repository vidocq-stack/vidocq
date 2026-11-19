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

import java.io.IOException;

import javax.xml.xpath.XPathExpressionException;

import io.vidocq.bodoni.core.internal.XPathEngine;
import io.vidocq.bodoni.core.internal.Xml;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.tagext.TagSupport;

/**
 * {@code <x:out>} (Tags 3.0 section 11.3): evaluates the XPath expression {@code select},
 * converts the result as the XPath {@code string()} function does and writes it to the
 * current {@code JspWriter}, escaping {@code < > & ' "} unless {@code escapeXml} is
 * {@code false}.
 */
public class OutTag extends TagSupport {

    private static final long serialVersionUID = 1L;

    private static final String ACTION = "x:out";

    private String select;
    private boolean escapeXml = true;

    /** @param select the XPath expression to evaluate */
    public void setSelect(String select) {
        this.select = select;
    }

    /** @param escapeXml whether {@code < > & ' "} are escaped (default {@code true}) */
    public void setEscapeXml(boolean escapeXml) {
        this.escapeXml = escapeXml;
    }

    @Override
    public int doEndTag() throws JspException {
        String text;
        try {
            text = XPathEngine.string(pageContext, XmlSupport.contextNode(this), select);
        } catch (XPathExpressionException e) {
            throw XmlSupport.xpathFailure(ACTION, select, e);
        }
        try {
            pageContext.getOut().write(escapeXml ? Xml.escape(text) : text);
        } catch (IOException e) {
            throw new JspTagException(ACTION + ": " + e.getMessage(), e);
        }
        return EVAL_PAGE;
    }

    @Override
    public void release() {
        super.release();
        select = null;
        escapeXml = true;
    }
}
