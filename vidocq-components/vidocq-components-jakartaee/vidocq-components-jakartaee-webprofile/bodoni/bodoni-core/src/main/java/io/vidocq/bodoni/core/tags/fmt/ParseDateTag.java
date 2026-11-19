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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import io.vidocq.bodoni.core.internal.I18n;

import jakarta.servlet.jsp.JspException;

/**
 * {@code <fmt:parseDate>} (Tags 3.0 §9.10): parses the string form of a date and/or time.
 *
 * <p>The value comes from the {@code value} attribute or, when it was not set, from the trimmed
 * body. The parse locale is {@code parseLocale} or else the formatting locale (§9.2). Parsing is
 * non-lenient and, like {@link DateFormat#parse(String)}, ignores text after a complete date
 * (BOD-010). A style-based format that fails is retried once with the U+202F and U+00A0 of its
 * pattern made ASCII spaces (BOD-011). Attributes persist until {@link #release()}, as
 * the {@code Tag} lifecycle lets a container reuse an instance without calling the setters of
 * unchanged attributes again.
 */
public class ParseDateTag extends DateTagSupport {

    private static final long serialVersionUID = 1L;
    private static final String ACTION = "fmt:parseDate";
    private static final char NARROW_NO_BREAK_SPACE = '\u202F';
    private static final char NO_BREAK_SPACE = '\u00A0';

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
        Locale locale = parseLocale(parseLocale, I18n.dateLocales(), text);
        DateFormat format = dateFormat(locale);
        ParsePosition first = new ParsePosition(0);
        Date date = parse(format, text, first);
        if (date == null && !hasPattern() && format instanceof SimpleDateFormat styled) {
            date = retryWithAsciiSpaces(styled, locale, text);
        }
        if (date == null) {
            throw unparseable(text, first.getErrorIndex());
        }
        return date;
    }

    /**
     * Non-lenient parse (§9.10): field values must be valid. Like {@link DateFormat#parse(String)},
     * text after a complete date is ignored (BOD-010).
     */
    private static Date parse(DateFormat format, String text, ParsePosition position) {
        format.setLenient(false);
        return format.parse(text, position);
    }

    /**
     * Since JDK 20 the CLDR styles of many locales put U+202F (or U+00A0) where users type an ASCII
     * space, such as before AM/PM in {@code en_US}. A style-based format that failed is tried once
     * more with those spaces in its pattern made ASCII (BOD-011); an explicit pattern never is.
     */
    private static Date retryWithAsciiSpaces(SimpleDateFormat styled, Locale locale, String text) {
        String pattern = styled.toPattern();
        if (pattern.indexOf(NARROW_NO_BREAK_SPACE) < 0 && pattern.indexOf(NO_BREAK_SPACE) < 0) {
            return null;
        }
        SimpleDateFormat ascii = new SimpleDateFormat(
                pattern.replace(NARROW_NO_BREAK_SPACE, ' ').replace(NO_BREAK_SPACE, ' '), locale);
        ascii.setTimeZone(styled.getTimeZone());
        return parse(ascii, text, new ParsePosition(0));
    }

    private static JspException unparseable(String text, int offset) {
        return new JspException(ACTION + ": cannot parse '" + text + "'",
                new ParseException("Unparseable date: \"" + text + "\"", Math.max(offset, 0)));
    }
}
