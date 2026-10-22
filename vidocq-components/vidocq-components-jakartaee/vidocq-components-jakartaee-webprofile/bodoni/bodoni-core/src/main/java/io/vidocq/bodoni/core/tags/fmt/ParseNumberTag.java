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
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.Locale;

import io.vidocq.bodoni.core.internal.I18n;
import io.vidocq.bodoni.core.internal.Locales;
import io.vidocq.bodoni.core.internal.Scopes;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.tagext.BodyTagSupport;

/**
 * {@code <fmt:parseNumber>} (Tags 3.0 §9.8): parses the string form of a number, a currency or
 * a percentage.
 *
 * <p>The value comes from the {@code value} attribute or, when it was not set, from the trimmed
 * body. The parse locale is {@code parseLocale} or else the formatting locale (§9.2).
 * Attributes persist until {@link #release()}, as the {@code Tag} lifecycle lets a container
 * reuse an instance without calling the setters of unchanged attributes again.
 */
public class ParseNumberTag extends BodyTagSupport {

    private static final long serialVersionUID = 1L;
    private static final String ACTION = "fmt:parseNumber";

    private String value;
    private boolean valueSet;
    private String type;
    private String pattern;
    private Object parseLocale;
    private boolean integerOnly;
    private String varName;
    private String scope;

    /** @param value the text to parse; {@code null} or empty removes {@code var} */
    public void setValue(String value) {
        this.value = value;
        this.valueSet = true;
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

    /** @param name the scoped variable receiving the {@link Number} instead of the output */
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
        pattern = null;
        parseLocale = null;
        integerOnly = false;
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

    private Number parse(String text) throws JspException {
        Locale locale = locale();
        if (locale == null) {
            throw new JspException(ACTION + ": no formatting locale to parse '" + text + "'");
        }
        NumberFormat format = FmtSupport.numberFormat(ACTION, type, pattern, locale);
        format.setParseIntegerOnly(integerOnly);
        try {
            return format.parse(text);
        } catch (ParseException e) {
            throw new JspException(ACTION + ": cannot parse '" + text + "'", e);
        }
    }

    private Locale locale() throws JspTagException {
        try {
            Locale explicit = Locales.of(parseLocale);
            return explicit != null ? explicit
                    : FmtSupport.formattingLocale(this, pageContext, I18n.NUMBER_LOCALES);
        } catch (IllegalArgumentException e) {
            throw new JspTagException(ACTION + ": " + e.getMessage(), e);
        }
    }

    private void emit(Number number, int scopeId) throws JspTagException {
        try {
            if (varName != null) {
                pageContext.setAttribute(varName, number, scopeId);
            } else {
                pageContext.getOut().print(number.toString());
            }
        } catch (IOException e) {
            throw new JspTagException(ACTION + ": " + e.getMessage(), e);
        }
    }
}
