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

    private String items;
    private String delims;
    private transient Iterator<?> iterator;

    /** @param items the string to tokenize */
    public void setItems(String items) {
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
        if (!prepareRange() || items == null) {
            iterator = Collections.emptyIterator();
        } else {
            iterator = ForEachTag.tokens(items, delims == null ? "" : delims);
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
        delims = null;
        iterator = null;
    }
}
