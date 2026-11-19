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
import jakarta.servlet.jsp.jstl.core.Config;

/**
 * {@code <fmt:setTimeZone>} (Tags 3.0 §9.6): stores a time zone in a scoped variable, or in the
 * {@code jakarta.servlet.jsp.jstl.fmt.timeZone} configuration setting when there is no
 * {@code var}.
 */
public class SetTimeZoneTag extends VarTagSupport {

    private static final long serialVersionUID = 1L;

    private static final String GMT = "GMT";

    private transient Object value;

    /** Creates the handler. */
    public SetTimeZoneTag() {
        super("fmt:setTimeZone");
    }

    /** @param value a time zone id or a {@link TimeZone}; {@code null} or empty means GMT */
    public void setValue(Object value) {
        this.value = value;
    }

    @Override
    public int doStartTag() throws JspException {
        int scopeId = scopeId();
        TimeZone zone = FmtSupport.convert(value);
        if (zone == null) {
            zone = TimeZone.getTimeZone(GMT);
        }
        store(zone, Config.FMT_TIME_ZONE, scopeId);
        return SKIP_BODY;
    }

    @Override
    public void release() {
        super.release();
        value = null;
    }
}
