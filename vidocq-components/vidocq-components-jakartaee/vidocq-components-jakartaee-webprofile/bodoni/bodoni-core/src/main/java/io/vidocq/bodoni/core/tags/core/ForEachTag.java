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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;

/**
 * {@code <c:forEach>} (Tags 3.0 §6.2): iterates over arrays, collections, maps, iterators,
 * enumerations and comma-separated strings, or over an integer range when {@code items} is
 * absent. {@code begin}, {@code end}, {@code step}, {@code var} and {@code varStatus} are
 * handled by {@link LoopTagSupport}. A deferred {@code items} expression makes the base class
 * map {@code var} to a per-item deferred expression for the body.
 */
public class ForEachTag extends LoopTagSupport {

    private static final long serialVersionUID = 1L;

    private Object rawItems;
    private boolean itemsSpecified;
    private transient Iterator<?> iterator;

    /** @param items the collection to iterate over (static value or deferred expression) */
    public void setItems(Object items) {
        this.rawItems = items;
        this.itemsSpecified = true;
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
    protected void prepare() throws JspTagException {
        if (beginSpecified) {
            validateBegin();
        }
        if (endSpecified) {
            validateEnd();
        }
        if (stepSpecified) {
            validateStep();
        }
        if (!itemsSpecified) {
            if (!endSpecified) {
                throw new JspTagException("<c:forEach> needs either items or end");
            }
            iterator = new CountingIterator(end);
            return;
        }
        Object value = rawItems;
        if (value instanceof ValueExpression expression) {
            deferredExpression = expression;
            value = expression.getValue(pageContext.getELContext());
        }
        iterator = toIterator(value);
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
            throw new JspTagException("<c:forEach> has no more items", e);
        }
    }

    @Override
    public void release() {
        super.release();
        rawItems = null;
        itemsSpecified = false;
        iterator = null;
        deferredExpression = null;
    }

    static Iterator<?> toIterator(Object value) throws JspTagException {
        return switch (value) {
            case null -> Collections.emptyIterator();
            case Object[] a -> java.util.Arrays.asList(a).iterator();
            case int[] a -> box(a.length, i -> a[i]);
            case long[] a -> box(a.length, i -> a[i]);
            case short[] a -> box(a.length, i -> a[i]);
            case byte[] a -> box(a.length, i -> a[i]);
            case char[] a -> box(a.length, i -> a[i]);
            case boolean[] a -> box(a.length, i -> a[i]);
            case float[] a -> box(a.length, i -> a[i]);
            case double[] a -> box(a.length, i -> a[i]);
            case Collection<?> c -> c.iterator();
            case Iterator<?> it -> it;
            case Enumeration<?> e -> Collections.list(e).iterator();
            case Map<?, ?> m -> m.entrySet().iterator();
            case String s -> tokens(s, ",");
            default -> throw new JspTagException("Unsupported type for <c:forEach> items: "
                    + value.getClass().getName());
        };
    }

    static Iterator<Object> tokens(String s, String delims) {
        StringTokenizer tokenizer = new StringTokenizer(s, delims);
        List<Object> list = new ArrayList<>();
        while (tokenizer.hasMoreTokens()) {
            list.add(tokenizer.nextToken());
        }
        return list.iterator();
    }

    private interface Element {
        Object at(int index);
    }

    private static Iterator<Object> box(int length, Element element) {
        List<Object> list = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            list.add(element.at(i));
        }
        return list.iterator();
    }

    /** Yields 0..last as {@link Integer}s; the base class discards the first {@code begin}. */
    private static final class CountingIterator implements Iterator<Object> {
        private final int last;
        private int current;

        CountingIterator(int last) {
            this.last = last;
        }

        @Override
        public boolean hasNext() {
            return current <= last;
        }

        @Override
        public Object next() {
            if (current > last) {
                throw new NoSuchElementException();
            }
            return current++;
        }
    }
}
