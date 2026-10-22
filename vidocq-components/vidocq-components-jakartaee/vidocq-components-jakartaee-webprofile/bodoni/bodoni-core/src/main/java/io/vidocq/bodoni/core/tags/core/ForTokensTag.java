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

import jakarta.el.ValueExpression;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.jstl.core.LoopTagSupport;

import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * {@code <c:forTokens>} (Tags 3.0 §6.3): iterates over the tokens of a string split on any
 * character of {@code delims}.
 */
public class ForTokensTag extends LoopTagSupport {

    private static final long serialVersionUID = 1L;

    private Object rawItems;
    private String delims;
    private transient Iterator<?> iterator;

    /** @param items the string to tokenize (static value or deferred expression) */
    public void setItems(Object items) {
        this.rawItems = items;
    }

    /** @param delims the delimiter characters */
    public void setDelims(String delims) {
        this.delims = delims;
    }

    /** @param begin the first index (zero-based, inclusive) */
    public void setBegin(int begin) {
        this.begin = begin;
        this.beginSpecified = true;
    }

    /** @param end the last index (inclusive) */
    public void setEnd(int end) {
        this.end = end;
        this.endSpecified = true;
    }

    /** @param step the iteration step (at least 1) */
    public void setStep(int step) {
        this.step = step;
        this.stepSpecified = true;
    }

    @Override
    protected String getDelims() {
        return delims;
    }

    @Override
    protected void prepare() throws JspTagException {
        if (beginSpecified) {
            validateBegin();
        }
        if (stepSpecified) {
            validateStep();
        }
        if (endSpecified && end < begin) {
            // Tags 3.0 §6.2/§6.3: end < begin means the loop is not executed (end may be -1)
            iterator = Collections.emptyIterator();
            return;
        }
        Object value = rawItems;
        if (value instanceof ValueExpression expression) {
            deferredExpression = expression;
            value = expression.getValue(pageContext.getELContext());
        }
        if (value == null) {
            iterator = Collections.emptyIterator();
        } else {
            iterator = ForEachTag.tokens(value.toString(), delims == null ? "" : delims);
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
        rawItems = null;
        delims = null;
        iterator = null;
        deferredExpression = null;
    }
}
