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

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.tagext.BodyTagSupport;

/**
 * {@code <x:param>} (Tags 3.0 section 13.3): sets a parameter of the enclosing
 * {@code <x:transform>}, its value given by the {@code value} attribute when set (even to
 * {@code null}), else by the trimmed body. A {@code null} value sets no parameter, so the
 * stylesheet's default applies.
 *
 * <p>The attributes persist until {@link #release()}; only the body is per-invocation.
 */
public class ParamTag extends BodyTagSupport {

    private static final long serialVersionUID = 1L;

    /** Message of an {@code <x:param>} outside any {@code <x:transform>}. */
    private static final String NOT_NESTED = "<x:param> must be nested in <x:transform>";

    private String name;
    private transient Object value;
    private boolean valueSpecified;

    /** @param name the name of the transformation parameter */
    public void setName(String name) {
        this.name = name;
    }

    /** @param value the value of the parameter; the body is then ignored */
    public void setValue(Object value) {
        this.value = value;
        this.valueSpecified = true;
    }

    @Override
    public int doStartTag() throws JspException {
        // An empty element gets no setBodyContent: never reuse a pooled handler's old body.
        bodyContent = null;
        return valueSpecified ? SKIP_BODY : EVAL_BODY_BUFFERED;
    }

    @Override
    public int doEndTag() throws JspException {
        if (!(findAncestorWithClass(this, TransformTag.class) instanceof TransformTag transform)) {
            throw new JspTagException(NOT_NESTED);
        }
        transform.addParameter(name, valueSpecified ? value : body());
        return EVAL_PAGE;
    }

    @Override
    public void release() {
        super.release();
        name = null;
        value = null;
        valueSpecified = false;
    }

    /** The trimmed body, {@code null} when no body was evaluated. */
    private String body() {
        return bodyContent == null ? null : bodyContent.getString().trim();
    }
}
