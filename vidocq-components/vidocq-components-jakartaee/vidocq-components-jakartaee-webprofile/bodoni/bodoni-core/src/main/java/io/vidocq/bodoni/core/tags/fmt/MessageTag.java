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

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import io.vidocq.bodoni.core.internal.I18n;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.jstl.fmt.LocalizationContext;
import jakarta.servlet.jsp.tagext.TagSupport;

/**
 * {@code <fmt:message>} (Tags 3.0 §8.8): looks up a localized message.
 *
 * <p>The localization context is, in order, the {@code bundle} attribute, the enclosing
 * {@code <fmt:bundle>} (whose prefix is then prepended to the key) and the default context
 * (§8.2). The key comes from the {@code key} attribute or, without one, from the trimmed body.
 * With {@code <fmt:param>} children the message goes through {@link MessageFormat}; without,
 * it is left unmodified.
 */
public class MessageTag extends VarTagSupport {

    private static final long serialVersionUID = 1L;
    private static final String ACTION = "fmt:message";

    private String key;
    private boolean keySet;
    private transient LocalizationContext bundle;
    private final transient List<Object> params = new ArrayList<>();

    /** Creates the handler. */
    public MessageTag() {
        super(ACTION);
    }

    /** @param key the message key; {@code null} or empty gives {@code ??????} */
    public void setKey(String key) {
        this.key = key;
        this.keySet = true;
    }

    /** @param bundle the localization context to look the key up in */
    public void setBundle(LocalizationContext bundle) {
        this.bundle = bundle;
    }

    /** Adds the next parameter of the parametric replacement. */
    void addParam(Object value) {
        params.add(value);
    }

    @Override
    public int doStartTag() throws JspException {
        // An empty element gets no setBodyContent: never reuse a pooled handler's old body.
        bodyContent = null;
        params.clear();
        scopeId();
        return EVAL_BODY_BUFFERED;
    }

    @Override
    public int doEndTag() throws JspException {
        int scopeId = scopeId();
        String message;
        try {
            message = resolve();
        } finally {
            params.clear();
        }
        export(message, scopeId);
        return EVAL_PAGE;
    }

    @Override
    public void release() {
        super.release();
        key = null;
        keySet = false;
        bundle = null;
        params.clear();
    }

    private String resolve() throws JspTagException {
        String name = keySet ? key : bodyValue();
        if (name == null || name.isEmpty()) {
            return "??????";
        }
        LocalizationContext context = bundle;
        if (context != null) {
            I18n.setResponseLocale(pageContext, context.getLocale());
        }
        if (TagSupport.findAncestorWithClass(this, BundleTag.class) instanceof BundleTag enclosing) {
            if (context == null) {
                context = enclosing.getLocalizationContext();
            }
            if (enclosing.getPrefix() != null) {
                name = enclosing.getPrefix() + name;
            }
        }
        if (context == null) {
            context = FmtSupport.defaultContext(ACTION, pageContext);
        }
        ResourceBundle resources = context == null ? null : context.getResourceBundle();
        if (resources == null) {
            return undefined(name);
        }
        String pattern;
        try {
            pattern = resources.getString(name);
        } catch (MissingResourceException _) {
            return undefined(name);
        }
        return params.isEmpty() ? pattern : format(pattern, context.getLocale());
    }

    /**
     * Parametric replacement (§8.8): the {@link MessageFormat} locale is the localization
     * context's; without one, the formatting locale lookup alone over the locales of both
     * number and date formatting; without a match, the runtime's default locale.
     */
    private String format(String pattern, Locale contextLocale) throws JspTagException {
        Locale locale = contextLocale;
        if (locale == null) {
            locale = FmtSupport.lookup(ACTION, pageContext, I18n.messageLocales());
        }
        if (locale == null) {
            locale = Locale.getDefault();
        }
        MessageFormat format = new MessageFormat("", locale);
        format.applyPattern(pattern);
        return format.format(params.toArray());
    }

    private static String undefined(String name) {
        return "???" + name + "???";
    }
}
