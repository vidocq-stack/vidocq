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
import jakarta.servlet.jsp.jstl.core.ConditionalTagSupport;

/**
 * {@code <c:if>} (Tags 3.0 §5.3): evaluates its body when {@code test} is true and optionally
 * exports the test result in {@code var} / {@code scope} (handled by the base class).
 */
public class IfTag extends ConditionalTagSupport {

    private static final long serialVersionUID = 1L;

    private boolean test;

    /** @param test the condition */
    public void setTest(boolean test) {
        this.test = test;
    }

    @Override
    protected boolean condition() throws JspTagException {
        return test;
    }

    @Override
    public void release() {
        super.release();
        test = false;
    }
}
