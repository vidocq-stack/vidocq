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

import java.util.Date;
import java.util.Locale;

import io.vidocq.bodoni.core.internal.I18n;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;

/**
 * {@code <fmt:formatDate>} (Tags 3.0 §9.9): formats the date and/or time of a
 * {@link Date} in the formatting locale (§9.2) and the time zone of §9.4.
 *
 * <p>The element has an empty body. When no formatting locale is found, the output is
 * {@link Date#toString()}. Attributes persist until {@link #release()}, as the {@code Tag}
 * lifecycle lets a container reuse an instance without calling the setters of unchanged
 * attributes again.
 */
public class FormatDateTag extends DateTagSupport {

    private static final long serialVersionUID = 1L;
    private static final String ACTION = "fmt:formatDate";

    /** Creates the handler. */
    public FormatDateTag() {
        super(ACTION);
    }

    /** @param value the date to format; {@code null} writes nothing */
    public void setValue(Date value) {
        valueAttribute(value);
    }

    @Override
    public int doStartTag() throws JspException {
        super.doStartTag();
        // The element is empty (§9.9): the value is the attribute alone, never a body.
        return SKIP_BODY;
    }

    @Override
    String convert(Object raw) throws JspTagException {
        Date value = (Date) raw;
        Locale locale = FmtSupport.formattingLocale(ACTION, this, pageContext, I18n.dateLocales());
        if (locale == null) {
            return value.toString();
        }
        return dateFormat(locale).format(value);
    }
}
