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
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

import io.vidocq.bodoni.core.internal.I18n;
import io.vidocq.bodoni.core.internal.Scopes;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.tagext.BodyTagSupport;

/**
 * {@code <fmt:formatNumber>} (Tags 3.0 §9.7): formats a number as a number, a currency or a
 * percentage, in the formatting locale (§9.2).
 *
 * <p>The value comes from the {@code value} attribute or, when it was not set, from the trimmed
 * body. The grouping, digit and currency options are applied only when their setter was called;
 * attributes persist until {@link #release()}, as the {@code Tag} lifecycle lets a container
 * reuse an instance without calling the setters of unchanged attributes again.
 */
public class FormatNumberTag extends BodyTagSupport {

    private static final long serialVersionUID = 1L;
    private static final String ACTION = "fmt:formatNumber";

    private Object value;
    private boolean valueSet;
    private String type;
    private String pattern;
    private String currencyCode;
    private String currencySymbol;
    private boolean groupingUsed;
    private boolean groupingUsedSet;
    private int maxIntegerDigits;
    private boolean maxIntegerDigitsSet;
    private int minIntegerDigits;
    private boolean minIntegerDigitsSet;
    private int maxFractionDigits;
    private boolean maxFractionDigitsSet;
    private int minFractionDigits;
    private boolean minFractionDigitsSet;
    private String varName;
    private String scope;

    /** @param value the number or its string form; {@code null} or empty writes nothing */
    public void setValue(Object value) {
        this.value = value;
        this.valueSet = true;
    }

    /** @param type {@code number} (default), {@code currency} or {@code percent} */
    public void setType(String type) {
        this.type = type;
    }

    /** @param pattern a {@link DecimalFormat} pattern, which takes precedence over {@code type} */
    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    /** @param currencyCode an ISO 4217 code, applied when {@code type} is {@code currency} */
    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    /** @param currencySymbol the symbol to use, applied when {@code type} is {@code currency} */
    public void setCurrencySymbol(String currencySymbol) {
        this.currencySymbol = currencySymbol;
    }

    /** @param groupingUsed whether the output contains grouping separators */
    public void setGroupingUsed(boolean groupingUsed) {
        this.groupingUsed = groupingUsed;
        this.groupingUsedSet = true;
    }

    /** @param digits the maximum number of integer digits */
    public void setMaxIntegerDigits(int digits) {
        this.maxIntegerDigits = digits;
        this.maxIntegerDigitsSet = true;
    }

    /** @param digits the minimum number of integer digits */
    public void setMinIntegerDigits(int digits) {
        this.minIntegerDigits = digits;
        this.minIntegerDigitsSet = true;
    }

    /** @param digits the maximum number of fraction digits */
    public void setMaxFractionDigits(int digits) {
        this.maxFractionDigits = digits;
        this.maxFractionDigitsSet = true;
    }

    /** @param digits the minimum number of fraction digits */
    public void setMinFractionDigits(int digits) {
        this.minFractionDigits = digits;
        this.minFractionDigitsSet = true;
    }

    /** @param name the scoped variable receiving the formatted text instead of the output */
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
        Object raw = valueSet ? value : bodyValue();
        if (raw == null || "".equals(raw)) {
            if (varName != null) {
                pageContext.removeAttribute(varName, scopeId);
            }
        } else {
            emit(format(raw), scopeId);
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
        currencyCode = null;
        currencySymbol = null;
        groupingUsedSet = false;
        maxIntegerDigitsSet = false;
        minIntegerDigitsSet = false;
        maxFractionDigitsSet = false;
        minFractionDigitsSet = false;
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

    private String format(Object raw) throws JspException {
        Number number = toNumber(raw);
        Locale locale = FmtSupport.formattingLocale(ACTION, this, pageContext, I18n.NUMBER_LOCALES);
        NumberFormat format = FmtSupport.numberFormat(ACTION, type, pattern, locale == null ? Locale.ROOT : locale);
        if (locale == null) {
            return number.toString();
        }
        configure(format);
        return format.format(number);
    }

    private Number toNumber(Object raw) throws JspException {
        if (raw instanceof Number number) {
            return number;
        }
        String text = raw.toString();
        try {
            return text.indexOf('.') < 0 ? Long.valueOf(text) : Double.valueOf(text);
        } catch (NumberFormatException e) {
            throw new JspException(ACTION + ": cannot parse '" + text + "' as a number", e);
        }
    }

    private void configure(NumberFormat format) throws JspTagException {
        if ("currency".equals(type) && format instanceof DecimalFormat decimal) {
            applyCurrency(decimal);
        }
        if (groupingUsedSet) {
            format.setGroupingUsed(groupingUsed);
        }
        if (maxIntegerDigitsSet) {
            format.setMaximumIntegerDigits(maxIntegerDigits);
        }
        if (minIntegerDigitsSet) {
            format.setMinimumIntegerDigits(minIntegerDigits);
        }
        if (maxFractionDigitsSet) {
            format.setMaximumFractionDigits(maxFractionDigits);
        }
        if (minFractionDigitsSet) {
            format.setMinimumFractionDigits(minFractionDigits);
        }
    }

    /** {@code currencyCode} takes precedence over {@code currencySymbol}. */
    private void applyCurrency(DecimalFormat format) throws JspTagException {
        if (currencyCode != null && !currencyCode.isEmpty()) {
            try {
                format.setCurrency(Currency.getInstance(currencyCode));
            } catch (IllegalArgumentException e) {
                throw new JspTagException(ACTION + ": invalid currencyCode '" + currencyCode + "'", e);
            }
        } else if (currencySymbol != null && !currencySymbol.isEmpty()) {
            DecimalFormatSymbols symbols = format.getDecimalFormatSymbols();
            symbols.setCurrencySymbol(currencySymbol);
            format.setDecimalFormatSymbols(symbols);
        }
    }

    private void emit(String text, int scopeId) throws JspTagException {
        try {
            if (varName != null) {
                pageContext.setAttribute(varName, text, scopeId);
            } else {
                pageContext.getOut().print(text);
            }
        } catch (IOException e) {
            throw new JspTagException(ACTION + ": " + e.getMessage(), e);
        }
    }
}
