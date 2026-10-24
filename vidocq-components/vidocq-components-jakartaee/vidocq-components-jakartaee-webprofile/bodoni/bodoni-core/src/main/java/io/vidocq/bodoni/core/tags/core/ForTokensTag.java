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

import jakarta.el.ELContext;
import jakarta.el.ValueExpression;
import jakarta.servlet.jsp.JspTagException;

import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * {@code <c:forTokens>} (Tags 3.0 §6.3): iterates over the tokens of a string split on any
 * character of {@code delims}; {@code begin}, {@code end} and {@code step} are handled by
 * {@link RangeLoopTagSupport}.
 */
public class ForTokensTag extends RangeLoopTagSupport {

    private static final long serialVersionUID = 1L;

    private Object items;
    private String delims;
    private transient Iterator<?> iterator;

    /** @param items the string to tokenize (static value or deferred expression) */
    public void setItems(Object items) {
        this.items = items;
    }

    /** @param delims the delimiter characters */
    public void setDelims(String delims) {
        this.delims = delims;
    }

    @Override
    protected String getDelims() {
        return delims;
    }

    @Override
    protected void prepare() throws JspTagException {
        if (!prepareRange()) {
            iterator = Collections.emptyIterator();
            return;
        }
        Object value = items;
        if (value instanceof ValueExpression expression) {
            value = expression.getValue(pageContext.getELContext());
            // LoopTagSupport splits the expression's own value, which must then be a String.
            deferredExpression = value == null || value instanceof String ? expression
                    : new StringCoercedExpression(expression);
        }
        if (value == null) {
            iterator = Collections.emptyIterator();
        } else {
            iterator = ForEachTag.tokens(asString(value), delims == null ? "" : delims);
        }
    }

    /** Coercion to {@code String} as EL 6.0 specifies it: an enum gives its name. */
    private static String asString(Object value) {
        return switch (value) {
            case String s -> s;
            case Enum<?> e -> e.name();
            default -> value.toString();
        };
    }

    /** A value expression whose value is coerced to {@code String}, for a non-String {@code items}. */
    private static final class StringCoercedExpression extends ValueExpression {

        private static final long serialVersionUID = 1L;

        private final ValueExpression delegate;

        StringCoercedExpression(ValueExpression delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object getValue(ELContext context) {
            Object value = delegate.getValue(context);
            return value == null ? null : asString(value);
        }

        @Override
        public void setValue(ELContext context, Object value) {
            delegate.setValue(context, value);
        }

        @Override
        public boolean isReadOnly(ELContext context) {
            return delegate.isReadOnly(context);
        }

        @Override
        public Class<?> getType(ELContext context) {
            return String.class;
        }

        @Override
        public Class<?> getExpectedType() {
            return String.class;
        }

        @Override
        public String getExpressionString() {
            return delegate.getExpressionString();
        }

        @Override
        public boolean isLiteralText() {
            return delegate.isLiteralText();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof StringCoercedExpression o && delegate.equals(o.delegate);
        }

        @Override
        public int hashCode() {
            return delegate.hashCode();
        }
    }

    @Override
    protected boolean hasNext() throws JspTagException {
        return iterator.hasNext();
    }

    @Override
    protected Object next() throws JspTagException {
        try {
            return iterator.next();
        } catch (NoSuchElementException e) {
            throw new JspTagException("<c:forTokens> has no more tokens", e);
        }
    }

    @Override
    public void release() {
        super.release();
        items = null;
        deferredExpression = null;
        delims = null;
        iterator = null;
    }
}
