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
import jakarta.servlet.jsp.jstl.core.LoopTagSupport;

/**
 * The {@code begin}, {@code end} and {@code step} attributes shared by the iterators
 * {@code <c:forEach>} (Tags 3.0 §6.2), {@code <c:forTokens>} (§6.3) and {@code <x:forEach>}
 * (§12.6). The setters record the value and its {@code …Specified} flag of
 * {@link LoopTagSupport}, which discards the items outside the range; a subclass calls
 * {@link #prepareRange()} first thing in {@code prepare()}.
 */
public abstract class RangeLoopTagSupport extends LoopTagSupport {

    private static final long serialVersionUID = 1L;

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

    /**
     * Validates the range of this invocation.
     *
     * @return {@code false} when {@code end} is specified and less than {@code begin}: the
     *         loop is then not executed (§6.2, §12.6; {@code end} may be {@code -1})
     * @throws JspTagException when {@code begin} is negative or {@code step} is less than 1
     */
    protected final boolean prepareRange() throws JspTagException {
        if (beginSpecified) {
            validateBegin();
        }
        if (stepSpecified) {
            validateStep();
        }
        return !endSpecified || end >= begin;
    }
}
