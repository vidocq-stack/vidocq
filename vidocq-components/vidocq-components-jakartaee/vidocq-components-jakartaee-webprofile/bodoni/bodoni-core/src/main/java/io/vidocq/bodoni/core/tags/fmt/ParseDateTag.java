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
import java.text.ParseException;
import java.text.ParsePosition;
import java.util.Date;
import java.util.Locale;

import io.vidocq.bodoni.core.internal.I18n;

import jakarta.servlet.jsp.JspException;

/**
 * {@code <fmt:parseDate>} (Tags 3.0 §9.10): parses the string form of a date and/or time.
 *
 * <p>The value comes from the {@code value} attribute or, when it was not set, from the trimmed
 * body. The parse locale is {@code parseLocale} or else the formatting locale (§9.2). Parsing is
 * non-lenient and must consume the whole value. Attributes persist until {@link #release()}, as
 * the {@code Tag} lifecycle lets a container reuse an instance without calling the setters of
 * unchanged attributes again.
 */
public class ParseDateTag extends DateTagSupport {

    private static final long serialVersionUID = 1L;
    private static final String ACTION = "fmt:parseDate";

    private transient Object parseLocale;

    /** Creates the handler. */
    public ParseDateTag() {
        super(ACTION);
    }

    /** @param value the text to parse; {@code null} or empty removes {@code var} */
    public void setValue(String value) {
        valueAttribute(value);
    }

    /** @param parseLocale a {@link Locale} or its text form; {@code null} or empty means missing */
    public void setParseLocale(Object parseLocale) {
        this.parseLocale = parseLocale;
    }

    @Override
    public void release() {
        super.release();
        parseLocale = null;
    }

    @Override
    Date convert(Object raw) throws JspException {
        String text = raw.toString();
        DateFormat format = dateFormat(parseLocale(parseLocale, I18n.dateLocales(), text));
        format.setLenient(false);
        ParsePosition position = new ParsePosition(0);
        Date date = format.parse(text, position);
        if (date == null) {
            throw unparseable(text, position.getErrorIndex());
        }
        if (position.getIndex() != text.length()) {
            throw unparseable(text, position.getIndex());
        }
        return date;
    }

    private static JspException unparseable(String text, int offset) {
        return new JspException(ACTION + ": cannot parse '" + text + "'",
                new ParseException("Unparseable date: \"" + text + "\"", Math.max(offset, 0)));
    }
}
