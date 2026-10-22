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

import io.vidocq.bodoni.core.internal.Scopes;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.tagext.TagSupport;

/**
 * {@code <c:remove>} (Tags 3.0 §4.4): removes a scoped variable, from one scope when
 * {@code scope} is given, from every scope otherwise.
 */
public class RemoveTag extends TagSupport {

    private static final long serialVersionUID = 1L;

    private String var;
    private String scope;

    /** @param var the name of the scoped variable to remove */
    public void setVar(String var) {
        this.var = var;
    }

    /** @param scope {@code page}, {@code request}, {@code session} or {@code application} */
    public void setScope(String scope) {
        this.scope = scope;
    }

    @Override
    public int doStartTag() throws JspException {
        if (var == null) {
            throw new JspTagException("c:remove: the var attribute is required");
        }
        if (scope == null) {
            pageContext.removeAttribute(var);
        } else {
            try {
                pageContext.removeAttribute(var, Scopes.of(scope));
            } catch (IllegalArgumentException e) {
                throw new JspTagException("c:remove: " + e.getMessage(), e);
            }
        }
        return SKIP_BODY;
    }

    @Override
    public void release() {
        super.release();
        var = null;
        scope = null;
    }
}
