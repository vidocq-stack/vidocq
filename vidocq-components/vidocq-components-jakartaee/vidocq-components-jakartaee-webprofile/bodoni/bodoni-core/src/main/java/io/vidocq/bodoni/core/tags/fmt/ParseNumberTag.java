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

import java.text.NumberFormat;
import java.text.ParseException;
import java.util.Locale;

import io.vidocq.bodoni.core.internal.I18n;

import jakarta.servlet.jsp.JspException;

/**
 * {@code <fmt:parseNumber>} (Tags 3.0 §9.8): parses the string form of a number, a currency or
 * a percentage.
 *
 * <p>The value comes from the {@code value} attribute or, when it was not set, from the trimmed
 * body. The parse locale is {@code parseLocale} or else the formatting locale (§9.2).
 * Attributes persist until {@link #release()}, as the {@code Tag} lifecycle lets a container
 * reuse an instance without calling the setters of unchanged attributes again.
 */
public class ParseNumberTag extends ValueTagSupport {

    private static final long serialVersionUID = 1L;
    private static final String ACTION = "fmt:parseNumber";

    private String type;
    private String pattern;
    private transient Object parseLocale;
    private boolean integerOnly;

    /** Creates the handler. */
    public ParseNumberTag() {
        super(ACTION);
    }

    /** @param value the text to parse; {@code null} or empty removes {@code var} */
    public void setValue(String value) {
        valueAttribute(value);
    }

    /** @param type {@code number} (default), {@code currency} or {@code percent} */
    public void setType(String type) {
        this.type = type;
    }

    /** @param pattern a {@link java.text.DecimalFormat} pattern, which takes precedence over {@code type} */
    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    /** @param parseLocale a {@link Locale} or its text form; {@code null} or empty means missing */
    public void setParseLocale(Object parseLocale) {
        this.parseLocale = parseLocale;
    }

    /** @param integerOnly whether just the integer part is parsed */
    public void setIntegerOnly(boolean integerOnly) {
        this.integerOnly = integerOnly;
    }

    @Override
    public void release() {
        super.release();
        type = null;
        pattern = null;
        parseLocale = null;
        integerOnly = false;
    }

    @Override
    Number convert(Object raw) throws JspException {
        String text = raw.toString();
        Locale locale = parseLocale(parseLocale, I18n.numberLocales(), text);
        NumberFormat format = FmtSupport.numberFormat(ACTION, type, pattern, locale);
        format.setParseIntegerOnly(integerOnly);
        try {
            return format.parse(text);
        } catch (ParseException e) {
            throw new JspException(ACTION + ": cannot parse '" + text + "'", e);
        }
    }
}
