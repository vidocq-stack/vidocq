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

import java.util.Map;

import io.vidocq.bodoni.core.internal.Scopes;

import jakarta.el.ELContext;
import jakarta.el.ELException;
import jakarta.el.ValueExpression;
import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.tagext.BodyTagSupport;

/**
 * {@code <c:set>} (Tags 3.0 §4.3): sets a scoped variable, a deferred-expression variable, or
 * a property of a {@code Map} / JavaBean target.
 *
 * <p>The value comes from the {@code value} attribute (even when it evaluates to
 * {@code null}) or, when the attribute is absent, from the trimmed body content.
 */
public class SetTag extends BodyTagSupport {

    private static final long serialVersionUID = 1L;

    private transient Object value;
    private boolean valueSet;
    private transient Object target;
    private boolean targetSet;
    private String property;
    private String varName;
    private String scope;
    private transient boolean buffered;

    /** @param value the value; {@code null} removes the variable or map key */
    public void setValue(Object value) {
        this.value = value;
        this.valueSet = true;
    }

    /** @param target the {@code Map} or JavaBean whose property is set */
    public void setTarget(Object target) {
        this.target = target;
        this.targetSet = true;
    }

    /** @param property the property or map key set on the target */
    public void setProperty(String property) {
        this.property = property;
    }

    /** @param name the name of the scoped variable */
    public void setVar(String name) {
        this.varName = name;
    }

    /** @param scope {@code page}, {@code request}, {@code session} or {@code application} */
    public void setScope(String scope) {
        this.scope = scope;
    }

    @Override
    public int doStartTag() throws JspException {
        // An empty element gets no setBodyContent: never reuse a pooled handler's old body.
        bodyContent = null;
        buffered = false;
        if (targetSet) {
            if (target == null) {
                throw new JspTagException("c:set: the target attribute evaluated to null");
            }
            if (property == null) {
                throw new JspTagException("c:set: the property attribute is required with target");
            }
        } else if (varName == null) {
            throw new JspTagException("c:set: either var or target is required");
        } else {
            scopeConstant();
        }
        if (valueSet) {
            return SKIP_BODY;
        }
        buffered = true;
        return EVAL_BODY_BUFFERED;
    }

    @Override
    public int doEndTag() throws JspException {
        Object result = value;
        if (!valueSet) {
            result = buffered && bodyContent != null ? bodyContent.getString().trim() : "";
        }
        buffered = false;
        if (targetSet) {
            setOnTarget(result);
        } else {
            setVariable(result);
        }
        return EVAL_PAGE;
    }

    private int scopeConstant() throws JspTagException {
        try {
            return Scopes.of(scope);
        } catch (IllegalArgumentException e) {
            throw new JspTagException("c:set: " + e.getMessage(), e);
        }
    }

    private void setVariable(Object result) throws JspTagException {
        int scopeId = scopeConstant();
        if (result instanceof ValueExpression expression) {
            pageContext.getELContext().getVariableMapper().setVariable(varName, expression);
            return;
        }
        if (result == null) {
            if (scope == null) {
                pageContext.removeAttribute(varName);
            } else {
                pageContext.removeAttribute(varName, scopeId);
            }
            return;
        }
        pageContext.setAttribute(varName, result, scopeId);
    }

    @SuppressWarnings("unchecked")
    private void setOnTarget(Object result) throws JspTagException {
        if (target instanceof Map<?, ?> map) {
            if (result == null) {
                map.remove(property);
            } else {
                ((Map<Object, Object>) map).put(property, result);
            }
            return;
        }
        ELContext context = pageContext.getELContext();
        try {
            context.setPropertyResolved(false);
            context.getELResolver().setValue(context, target, property, result);
            if (!context.isPropertyResolved()) {
                throw new JspTagException("c:set: cannot set property '" + property + "' on "
                        + target.getClass().getName());
            }
        } catch (ELException e) {
            throw new JspTagException("c:set: cannot set property '" + property + "' on "
                    + target.getClass().getName() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void release() {
        super.release();
        value = null;
        valueSet = false;
        target = null;
        targetSet = false;
        property = null;
        varName = null;
        scope = null;
        buffered = false;
    }
}
