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

import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.jstl.core.ConditionalTagSupport;

/**
 * {@code <x:if>} (Tags 3.0 section 12.2): evaluates the XPath expression {@code select},
 * converts the result as the XPath {@code boolean()} function does (section 12.1) and
 * renders its body when it is true; the result is exported in {@code var} / {@code scope}
 * as a {@code Boolean} by the base class.
 */
public class IfTag extends ConditionalTagSupport {

    private static final long serialVersionUID = 1L;

    private static final String ACTION = "x:if";

    private String select;
    private String scopeName;

    /** @param select the XPath expression to evaluate */
    public void setSelect(String select) {
        this.select = select;
    }

    /**
     * {@inheritDoc} The name is checked when the action runs: an unknown one is a
     * {@link JspTagException}, where the base class would accept it.
     */
    @Override
    public void setScope(String scope) {
        super.setScope(scope);
        this.scopeName = scope;
    }

    @Override
    protected boolean condition() throws JspTagException {
        XmlSupport.scopeId(ACTION, scopeName);
        return XmlSupport.test(ACTION, this, pageContext, select);
    }

    @Override
    public void release() {
        super.release();
        select = null;
        scopeName = null;
    }
}
