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
import jakarta.servlet.jsp.tagext.TagSupport;

/**
 * {@code <c:choose>} (Tags 3.0 §5.4): mutually exclusive conditional block. It remembers
 * whether one of its {@code <c:when>} children has already been taken.
 */
public class ChooseTag extends TagSupport {

    private static final long serialVersionUID = 1L;

    private boolean branchTaken;

    @Override
    public int doStartTag() throws JspException {
        branchTaken = false;
        return EVAL_BODY_INCLUDE;
    }

    /** @return whether a branch ({@code when} or {@code otherwise}) has already been taken */
    boolean branchTaken() {
        return branchTaken;
    }

    /** Records that a branch has been taken. */
    void markBranchTaken() {
        branchTaken = true;
    }

    @Override
    public void release() {
        super.release();
        branchTaken = false;
    }
}
