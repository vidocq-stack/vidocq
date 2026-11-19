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
import jakarta.servlet.jsp.PageContext;
import jakarta.servlet.jsp.tagext.TagSupport;
import jakarta.servlet.jsp.tagext.TryCatchFinally;

/**
 * {@code <c:catch>} (Tags 3.0 §4.5): evaluates its body and catches any {@link Throwable};
 * the exception is exposed in the page-scoped variable {@code var} (removed when the body
 * completes normally).
 */
public class CatchTag extends TagSupport implements TryCatchFinally {

    private static final long serialVersionUID = 1L;

    private String varName;

    /** @param name the page-scoped variable receiving the caught exception */
    public void setVar(String name) {
        this.varName = name;
    }

    @Override
    public int doStartTag() throws JspException {
        if (varName != null) {
            pageContext.removeAttribute(varName, PageContext.PAGE_SCOPE);
        }
        return EVAL_BODY_INCLUDE;
    }

    @Override
    public void doCatch(Throwable t) {
        if (varName != null) {
            pageContext.setAttribute(varName, t, PageContext.PAGE_SCOPE);
        }
    }

    @Override
    public void doFinally() {
        // nothing to clean up: all state lives in the page context
    }

    @Override
    public void release() {
        super.release();
        varName = null;
    }
}
