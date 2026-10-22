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
import java.util.Date;
import java.util.Locale;

import io.vidocq.bodoni.core.internal.I18n;
import io.vidocq.bodoni.core.internal.Scopes;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.tagext.TagSupport;

/**
 * {@code <fmt:formatDate>} (Tags 3.0 §9.9): formats the date and/or time of a
 * {@link Date} in the formatting locale (§9.2) and the time zone of §9.4.
 *
 * <p>The element has an empty body. When no formatting locale is found, the output is
 * {@link Date#toString()}. Attributes persist until {@link #release()}, as the {@code Tag}
 * lifecycle lets a container reuse an instance without calling the setters of unchanged
 * attributes again.
 */
public class FormatDateTag extends TagSupport {

    private static final long serialVersionUID = 1L;
    private static final String ACTION = "fmt:formatDate";

    private Date value;
    private String type;
    private String dateStyle;
    private String timeStyle;
    private String pattern;
    private Object timeZone;
    private String varName;
    private String scope;

    /** @param value the date to format; {@code null} writes nothing */
    public void setValue(Date value) {
        this.value = value;
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
        scopeId();
        return SKIP_BODY;
    }

    @Override
    public int doEndTag() throws JspException {
        int scopeId = scopeId();
        if (value == null) {
            if (varName != null) {
                pageContext.removeAttribute(varName, scopeId);
            }
        } else {
            emit(format(), scopeId);
        }
        return EVAL_PAGE;
    }

    @Override
    public void release() {
        super.release();
        varName = null;
        scope = null;
        value = null;
        type = null;
        dateStyle = null;
        timeStyle = null;
        pattern = null;
        timeZone = null;
    }

    private int scopeId() throws JspTagException {
        try {
            return Scopes.of(scope);
        } catch (IllegalArgumentException e) {
            throw new JspTagException(ACTION + ": " + e.getMessage(), e);
        }
    }

    private String format() throws JspTagException {
        Locale locale = FmtSupport.formattingLocale(this, pageContext, I18n.DATE_LOCALES);
        if (locale == null) {
            return value.toString();
        }
        return FmtSupport.dateFormat(ACTION, type, dateStyle, timeStyle, pattern, locale,
                FmtSupport.timeZone(this, pageContext, timeZone)).format(value);
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
