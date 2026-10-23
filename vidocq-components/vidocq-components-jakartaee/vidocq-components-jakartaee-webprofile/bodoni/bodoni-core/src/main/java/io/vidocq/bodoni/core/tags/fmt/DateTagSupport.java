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

import java.text.DateFormat;
import java.util.Locale;

import jakarta.servlet.jsp.JspTagException;

/**
 * The attributes {@code <fmt:formatDate>} (§9.9) and {@code <fmt:parseDate>} (§9.10) share:
 * {@code type}, {@code dateStyle}, {@code timeStyle}, {@code pattern} and {@code timeZone},
 * which together give the {@link DateFormat} of the action.
 */
abstract class DateTagSupport extends ValueTagSupport {

    private static final long serialVersionUID = 1L;

    private String type;
    private String dateStyle;
    private String timeStyle;
    private String pattern;
    private transient Object timeZone;

    /** @param action the action name, for messages */
    DateTagSupport(String action) {
        super(action);
    }

    /** @param type {@code date} (default), {@code time} or {@code both} */
    public void setType(String type) {
        this.type = type;
    }

    /** @param dateStyle {@code default}, {@code short}, {@code medium}, {@code long} or {@code full} */
    public void setDateStyle(String dateStyle) {
        this.dateStyle = dateStyle;
    }

    /** @param timeStyle {@code default}, {@code short}, {@code medium}, {@code long} or {@code full} */
    public void setTimeStyle(String timeStyle) {
        this.timeStyle = timeStyle;
    }

    /** @param pattern a {@link java.text.SimpleDateFormat} pattern, which takes precedence over type and styles */
    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    /** @param timeZone a time zone id or a {@link java.util.TimeZone}; {@code null} or empty means missing */
    public void setTimeZone(Object timeZone) {
        this.timeZone = timeZone;
    }

    @Override
    public void release() {
        super.release();
        type = null;
        dateStyle = null;
        timeStyle = null;
        pattern = null;
        timeZone = null;
    }

    /** The date format of the attributes in {@code locale}, set to the time zone of §9.4. */
    final DateFormat dateFormat(Locale locale) throws JspTagException {
        return FmtSupport.dateFormat(action(), type, dateStyle, timeStyle, pattern, locale,
                FmtSupport.timeZone(this, pageContext, timeZone));
    }
}
