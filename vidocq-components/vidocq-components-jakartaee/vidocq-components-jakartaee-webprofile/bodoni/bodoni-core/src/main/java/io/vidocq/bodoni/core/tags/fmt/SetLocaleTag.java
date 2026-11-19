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

import java.util.Locale;

import io.vidocq.bodoni.core.internal.I18n;
import io.vidocq.bodoni.core.internal.Locales;
import io.vidocq.bodoni.core.internal.Scopes;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.jstl.core.Config;
import jakarta.servlet.jsp.tagext.TagSupport;

/**
 * {@code <fmt:setLocale>} (Tags 3.0 §8.5): stores a locale in the
 * {@code jakarta.servlet.jsp.jstl.fmt.locale} configuration setting and sets the response locale.
 */
public class SetLocaleTag extends TagSupport {

    private static final long serialVersionUID = 1L;

    private transient Object value;
    private String variant;
    private String scope;

    /** @param value a {@link Locale}, or the printable form of one; {@code null} or empty means the runtime default */
    public void setValue(Object value) {
        this.value = value;
    }

    /** @param variant the vendor- or browser-specific variant; ignored when the value is a {@link Locale} */
    public void setVariant(String variant) {
        this.variant = variant;
    }

    /** @param scope the scope of the configuration setting */
    public void setScope(String scope) {
        this.scope = scope;
    }

    @Override
    public int doStartTag() throws JspException {
        int scopeId;
        Locale locale;
        try {
            scopeId = Scopes.of(scope);
            locale = locale();
        } catch (IllegalArgumentException e) {
            throw new JspTagException("fmt:setLocale: " + e.getMessage(), e);
        }
        Config.set(pageContext, Config.FMT_LOCALE, locale, scopeId);
        I18n.setResponseLocale(pageContext, locale);
        return SKIP_BODY;
    }

    private Locale locale() {
        if (value instanceof Locale locale) {
            return locale;
        }
        Locale parsed = Locales.parse(value == null ? null : value.toString(), variant);
        return parsed != null ? parsed : Locale.getDefault();
    }

    @Override
    public void release() {
        super.release();
        value = null;
        variant = null;
        scope = null;
    }
}
