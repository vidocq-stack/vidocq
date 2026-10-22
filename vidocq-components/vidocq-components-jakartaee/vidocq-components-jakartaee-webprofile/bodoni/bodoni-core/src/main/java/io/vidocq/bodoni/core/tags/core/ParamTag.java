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

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.tagext.BodyTagSupport;
import jakarta.servlet.jsp.tagext.TagSupport;

/**
 * {@code <c:param>} (Tags 3.0 §7.7): adds a request parameter to the enclosing
 * {@code <c:url>}, {@code <c:redirect>} or {@code <c:import>}.
 *
 * <p>A {@code null} or empty {@code name} is ignored; a {@code null} value is an empty value.
 * Without a {@code value} attribute the value is the trimmed body content. Encoding is done
 * by the parent when it builds the URL.
 */
public class ParamTag extends BodyTagSupport {

    private static final long serialVersionUID = 1L;

    private String name;
    private String value;
    private boolean valueSet;
    private transient boolean buffered;

    /** @param name the parameter name; {@code null} or empty makes the action a no-op */
    public void setName(String name) {
        this.name = name;
    }

    /** @param value the parameter value; {@code null} is an empty value */
    public void setValue(String value) {
        this.value = value;
        this.valueSet = true;
    }

    @Override
    public int doStartTag() throws JspException {
        buffered = false;
        if (TagSupport.findAncestorWithClass(this, ParamParent.class) == null) {
            throw new JspTagException("c:param must be nested in c:url, c:redirect or c:import");
        }
        if (valueSet) {
            return SKIP_BODY;
        }
        buffered = true;
        return EVAL_BODY_BUFFERED;
    }

    @Override
    public int doEndTag() throws JspException {
        String text = value;
        if (!valueSet) {
            text = buffered && bodyContent != null ? bodyContent.getString().trim() : "";
        }
        buffered = false;
        if (name != null && !name.isEmpty()) {
            ParamParent parent = (ParamParent) TagSupport.findAncestorWithClass(this, ParamParent.class);
            try {
                parent.addParameter(name, text == null ? "" : text);
            } catch (IllegalStateException e) {
                throw new JspTagException("c:param: " + e.getMessage(), e);
            }
        }
        return EVAL_PAGE;
    }

    @Override
    public void release() {
        super.release();
        name = null;
        value = null;
        valueSet = false;
        buffered = false;
    }
}
