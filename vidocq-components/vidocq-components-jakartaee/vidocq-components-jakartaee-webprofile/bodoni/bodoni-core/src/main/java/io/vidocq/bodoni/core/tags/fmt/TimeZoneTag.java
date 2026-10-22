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

package io.vidocq.bodoni.core.tags.fmt;

import java.util.TimeZone;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.tagext.TagSupport;

/**
 * {@code <fmt:timeZone>} (Tags 3.0 §9.5): sets the time zone of the formatting and parsing
 * actions in its body, which read it through {@link #getTimeZone()}.
 */
public class TimeZoneTag extends TagSupport {

    private static final long serialVersionUID = 1L;

    private static final String GMT = "GMT";

    private transient Object value;
    private transient TimeZone timeZone;

    /** @param value a time zone id or a {@link TimeZone}; {@code null} or empty means GMT */
    public void setValue(Object value) {
        this.value = value;
    }

    /** @return the time zone of the body; {@code null} outside it */
    public TimeZone getTimeZone() {
        return timeZone;
    }

    @Override
    public int doStartTag() throws JspException {
        TimeZone zone = FmtSupport.convert(value);
        timeZone = zone != null ? zone : TimeZone.getTimeZone(GMT);
        return EVAL_BODY_INCLUDE;
    }

    @Override
    public void release() {
        super.release();
        value = null;
        timeZone = null;
    }
}
