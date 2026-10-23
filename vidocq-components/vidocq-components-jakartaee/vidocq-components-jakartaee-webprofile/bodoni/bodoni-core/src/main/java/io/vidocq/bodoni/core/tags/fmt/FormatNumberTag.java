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

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

import io.vidocq.bodoni.core.internal.I18n;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;

/**
 * {@code <fmt:formatNumber>} (Tags 3.0 §9.7): formats a number as a number, a currency or a
 * percentage, in the formatting locale (§9.2).
 *
 * <p>The value comes from the {@code value} attribute or, when it was not set, from the trimmed
 * body. The grouping, digit and currency options are applied only when their setter was called;
 * attributes persist until {@link #release()}, as the {@code Tag} lifecycle lets a container
 * reuse an instance without calling the setters of unchanged attributes again.
 */
public class FormatNumberTag extends ValueTagSupport {

    private static final long serialVersionUID = 1L;
    private static final String ACTION = "fmt:formatNumber";

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

    /** Creates the handler. */
    public FormatNumberTag() {
        super(ACTION);
    }

    /** @param value the number or its string form; {@code null} or empty writes nothing */
    public void setValue(Object value) {
        valueAttribute(value);
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

    @Override
    public void release() {
        super.release();
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

    @Override
    String convert(Object raw) throws JspException {
        Number number = toNumber(raw);
        Locale locale = FmtSupport.formattingLocale(ACTION, this, pageContext, I18n.numberLocales());
        if (locale == null) {
            // Without a formatting locale the output is toString() (§9.7), yet an invalid type or
            // pattern is still an error: the format is built in the root locale only to check them.
            FmtSupport.numberFormat(ACTION, type, pattern, Locale.ROOT);
            return number.toString();
        }
        NumberFormat format = FmtSupport.numberFormat(ACTION, type, pattern, locale);
        configure(format);
        return format.format(number);
    }

    /** A string without a decimal point is a {@link Long}, any other a {@link Double} (§9.7). */
    private static Number toNumber(Object raw) throws JspException {
        if (raw instanceof Number number) {
            return number;
        }
        String text = raw.toString();
        try {
            // Two returns, not a conditional: a numeric conditional would widen the Long to a double.
            if (text.indexOf('.') < 0) {
                return Long.valueOf(text);
            }
            return Double.valueOf(text);
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
}
