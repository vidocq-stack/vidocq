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

package io.vidocq.bodoni.core.tags.fmt;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.tagext.BodyTagSupport;
import jakarta.servlet.jsp.tagext.TagSupport;

/**
 * {@code <fmt:param>} (Tags 3.0 §8.9): supplies one parameter for the parametric replacement of
 * the enclosing {@code <fmt:message>}. Without a {@code value} attribute the parameter is the
 * trimmed body content.
 */
public class ParamTag extends BodyTagSupport {

    private static final long serialVersionUID = 1L;

    private Object value;
    private boolean valueSet;
    private transient boolean buffered;

    /** @param value the parameter; {@code null} is a {@code null} argument of the message format */
    public void setValue(Object value) {
        this.value = value;
        this.valueSet = true;
    }

    @Override
    public int doStartTag() throws JspException {
        // An empty element gets no setBodyContent: never reuse a pooled handler's old body.
        bodyContent = null;
        buffered = false;
        if (!(TagSupport.findAncestorWithClass(this, MessageTag.class) instanceof MessageTag)) {
            throw new JspTagException("fmt:param must be nested in fmt:message");
        }
        if (valueSet) {
            return SKIP_BODY;
        }
        buffered = true;
        return EVAL_BODY_BUFFERED;
    }

    @Override
    public int doEndTag() throws JspException {
        Object parameter = value;
        if (!valueSet) {
            parameter = buffered && bodyContent != null ? bodyContent.getString().trim() : "";
        }
        buffered = false;
        ((MessageTag) TagSupport.findAncestorWithClass(this, MessageTag.class)).addParam(parameter);
        return EVAL_PAGE;
    }

    @Override
    public void release() {
        super.release();
        value = null;
        valueSet = false;
        buffered = false;
    }
}
