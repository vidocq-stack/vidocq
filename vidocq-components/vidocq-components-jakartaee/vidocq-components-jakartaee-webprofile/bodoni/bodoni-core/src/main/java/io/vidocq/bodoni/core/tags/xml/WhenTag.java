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
 * {@code <x:when>} (Tags 3.0 section 12.4): a branch of {@code <x:choose>}; its body is
 * rendered when no earlier branch was taken and {@code select}, converted by the XPath
 * {@code boolean()} function, is true. Once a branch is taken, {@code select} is not
 * evaluated.
 */
public class WhenTag extends ConditionalTagSupport {

    private static final long serialVersionUID = 1L;

    private static final String ACTION = "x:when";

    private String select;

    /** @param select the XPath expression to evaluate */
    public void setSelect(String select) {
        this.select = select;
    }

    @Override
    protected boolean condition() throws JspTagException {
        if (!(getParent() instanceof ChooseTag choose)) {
            throw new JspTagException("<x:when> must be a direct child of <x:choose>");
        }
        if (choose.branchTaken() || !XmlSupport.test(ACTION, this, pageContext, select)) {
            return false;
        }
        choose.markBranchTaken();
        return true;
    }

    @Override
    public void release() {
        super.release();
        select = null;
    }
}
