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

import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.ParsePosition;
import java.util.Date;
import java.util.Locale;

import io.vidocq.bodoni.core.internal.I18n;
import io.vidocq.bodoni.core.internal.Locales;
import io.vidocq.bodoni.core.internal.Scopes;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.tagext.BodyTagSupport;

/**
 * {@code <fmt:parseDate>} (Tags 3.0 §9.10): parses the string form of a date and/or time.
 *
 * <p>The value comes from the {@code value} attribute or, when it was not set, from the trimmed
 * body. The parse locale is {@code parseLocale} or else the formatting locale (§9.2). Parsing is
 * non-lenient and must consume the whole value. Attributes persist until {@link #release()}, as
 * the {@code Tag} lifecycle lets a container reuse an instance without calling the setters of
 * unchanged attributes again.
 */
public class ParseDateTag extends BodyTagSupport {

    private static final long serialVersionUID = 1L;
    private static final String ACTION = "fmt:parseDate";

    private String value;
    private boolean valueSet;
    private String type;
    private String dateStyle;
    private String timeStyle;
    private String pattern;
    private transient Object timeZone;
    private transient Object parseLocale;
    private String varName;
    private String scope;

    /** @param value the text to parse; {@code null} or empty removes {@code var} */
    public void setValue(String value) {
        this.value = value;
        this.valueSet = true;
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

    /** @param parseLocale a {@link Locale} or its text form; {@code null} or empty means missing */
    public void setParseLocale(Object parseLocale) {
        this.parseLocale = parseLocale;
    }

    /** @param name the scoped variable receiving the {@link Date} instead of the output */
    public void setVar(String name) {
        this.varName = name;
    }

    /** @param scope the scope of {@code var} */
    public void setScope(String scope) {
        this.scope = scope;
    }

    @Override
    public int doStartTag() throws JspException {
        // An empty element gets no setBodyContent: never reuse a pooled handler's old body.
        bodyContent = null;
        scopeId();
        return valueSet ? SKIP_BODY : EVAL_BODY_BUFFERED;
    }

    @Override
    public int doEndTag() throws JspException {
        int scopeId = scopeId();
        String text = valueSet ? value : bodyValue();
        if (text == null || text.isEmpty()) {
            if (varName != null) {
                pageContext.removeAttribute(varName, scopeId);
            }
        } else {
            emit(parse(text), scopeId);
        }
        return EVAL_PAGE;
    }

    @Override
    public void release() {
        super.release();
        varName = null;
        scope = null;
        value = null;
        valueSet = false;
        type = null;
        dateStyle = null;
        timeStyle = null;
        pattern = null;
        timeZone = null;
        parseLocale = null;
    }

    private int scopeId() throws JspTagException {
        try {
            return Scopes.of(scope);
        } catch (IllegalArgumentException e) {
            throw new JspTagException(ACTION + ": " + e.getMessage(), e);
        }
    }

    private String bodyValue() {
        return bodyContent == null ? null : bodyContent.getString().trim();
    }

    private Date parse(String text) throws JspException {
        Locale locale = locale();
        if (locale == null) {
            throw new JspException(ACTION + ": no formatting locale to parse '" + text + "'");
        }
        DateFormat format = FmtSupport.dateFormat(ACTION, type, dateStyle, timeStyle, pattern, locale,
                FmtSupport.timeZone(this, pageContext, timeZone));
        format.setLenient(false);
        ParsePosition position = new ParsePosition(0);
        Date date = format.parse(text, position);
        if (date == null) {
            throw failure(text, position.getErrorIndex());
        }
        if (position.getIndex() != text.length()) {
            throw failure(text, position.getIndex());
        }
        return date;
    }

    private static JspException failure(String text, int offset) {
        return new JspException(ACTION + ": cannot parse '" + text + "'",
                new ParseException("Unparseable date: \"" + text + "\"", Math.max(offset, 0)));
    }

    private Locale locale() throws JspTagException {
        Locale explicit;
        try {
            explicit = Locales.of(parseLocale);
        } catch (IllegalArgumentException e) {
            throw new JspTagException(ACTION + ": " + e.getMessage(), e);
        }
        return explicit != null ? explicit
                : FmtSupport.formattingLocale(ACTION, this, pageContext, I18n.dateLocales());
    }

    private void emit(Date date, int scopeId) throws JspTagException {
        try {
            if (varName != null) {
                pageContext.setAttribute(varName, date, scopeId);
            } else {
                pageContext.getOut().print(date.toString());
            }
        } catch (IOException e) {
            throw new JspTagException(ACTION + ": " + e.getMessage(), e);
        }
    }
}
