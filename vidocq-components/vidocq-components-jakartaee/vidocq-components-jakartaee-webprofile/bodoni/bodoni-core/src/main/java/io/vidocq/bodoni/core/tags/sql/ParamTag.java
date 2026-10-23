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

package io.vidocq.bodoni.core.tags.sql;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.jstl.sql.SQLExecutionTag;
import jakarta.servlet.jsp.tagext.BodyTagSupport;

/**
 * {@code <sql:param>} (Tags 3.0 §10.7): sets the next parameter marker of the enclosing
 * {@code <sql:query>} or {@code <sql:update>} (any {@link SQLExecutionTag}) to the
 * {@code value} attribute, which may be {@code null} (the SQL value NULL), or else to the
 * trimmed body, {@code null} when there is none.
 */
public class ParamTag extends BodyTagSupport {

    private static final long serialVersionUID = 1L;

    private static final String ELEMENT = "sql:param";

    private transient Object value;
    private boolean valueSpecified;

    /** Creates the handler. */
    public ParamTag() {
        // attributes arrive through the setters
    }

    /** @param value the parameter value; a {@code null} value is the SQL NULL, not "use the body" */
    public void setValue(Object value) {
        this.value = value;
        this.valueSpecified = true;
    }

    @Override
    public int doStartTag() {
        // An empty element gets no setBodyContent: never reuse a pooled handler's old body.
        bodyContent = null;
        return valueSpecified ? SKIP_BODY : EVAL_BODY_BUFFERED;
    }

    @Override
    public int doEndTag() throws JspException {
        SQLExecutionTag target = SqlSupport.executionAncestor(this, ELEMENT);
        target.addSQLParameter(valueSpecified ? value : body());
        return EVAL_PAGE;
    }

    @Override
    public void release() {
        super.release();
        value = null;
        valueSpecified = false;
    }

    private String body() {
        return bodyContent == null ? null : bodyContent.getString().trim();
    }
}
