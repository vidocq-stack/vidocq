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
import java.text.NumberFormat;
import java.util.Locale;
import java.util.TimeZone;

import io.vidocq.bodoni.core.internal.I18n;

import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.PageContext;
import jakarta.servlet.jsp.jstl.core.Config;
import jakarta.servlet.jsp.jstl.fmt.LocalizationContext;
import jakarta.servlet.jsp.tagext.Tag;
import jakarta.servlet.jsp.tagext.TagSupport;

/**
 * What the formatting actions share: the formatting locale (Tags 3.0 §9.2) and the time zone
 * (§9.4) of an action, found from its enclosing actions, the configuration settings and the
 * request.
 */
final class FmtSupport {

    private FmtSupport() {
    }

    /**
     * The default i18n localization context (§9.2, second step): the
     * {@code jakarta.servlet.jsp.jstl.fmt.localizationContext} configuration setting. A
     * {@link LocalizationContext} is used as is; a {@code String} is a bundle basename, resolved
     * by the resource bundle determination algorithm, and the response locale is set from the
     * result.
     *
     * @return the context, or {@code null} when the setting is absent
     */
    static LocalizationContext defaultContext(PageContext pc) {
        Object setting = Config.find(pc, Config.FMT_LOCALIZATION_CONTEXT);
        if (setting instanceof LocalizationContext context) {
            return context;
        }
        if (setting instanceof String basename) {
            LocalizationContext context = I18n.determine(pc, basename);
            I18n.setResponseLocale(pc, context.getLocale());
            return context;
        }
        return null;
    }

    /**
     * The formatting locale of an action (§9.2): the locale of the nearest enclosing
     * {@code <fmt:bundle>}, else the locale of the default localization context, else the
     * locale found by lookup among {@code available} (which is then also the response locale).
     *
     * @return the locale, or {@code null} when none applies
     */
    static Locale formattingLocale(Tag tag, PageContext pc, Locale[] available) {
        if (TagSupport.findAncestorWithClass(tag, BundleTag.class) instanceof BundleTag bundle) {
            LocalizationContext context = bundle.getLocalizationContext();
            if (context != null && context.getLocale() != null) {
                return context.getLocale();
            }
        }
        LocalizationContext context = defaultContext(pc);
        if (context != null && context.getLocale() != null) {
            return context.getLocale();
        }
        Locale found = I18n.formattingLocale(pc, available);
        if (found != null) {
            I18n.setResponseLocale(pc, found);
        }
        return found;
    }

    /**
     * The time zone of an action (§9.4): the attribute, else the nearest enclosing
     * {@code <fmt:timeZone>}, else the {@code jakarta.servlet.jsp.jstl.fmt.timeZone}
     * configuration setting, else the container's time zone.
     *
     * @param attribute the {@code timeZone} attribute: a {@code String} id, a {@link TimeZone},
     *                  or {@code null} / empty when not specified
     */
    static TimeZone timeZone(Tag tag, PageContext pc, Object attribute) {
        TimeZone fromAttribute = convert(attribute);
        if (fromAttribute != null) {
            return fromAttribute;
        }
        if (TagSupport.findAncestorWithClass(tag, TimeZoneTag.class) instanceof TimeZoneTag enclosing
                && enclosing.getTimeZone() != null) {
            return enclosing.getTimeZone();
        }
        TimeZone fromConfig = convert(Config.find(pc, Config.FMT_TIME_ZONE));
        return fromConfig != null ? fromConfig : TimeZone.getDefault();
    }

    /**
     * The number format of an action (§9.7, §9.8): the locale's number, currency or percent
     * instance by {@code type} (default {@code number}), with a non-empty {@code pattern}
     * applied on top of it (the pattern takes precedence over the type; the symbols stay the
     * locale's).
     *
     * @param action the action name, for messages
     * @throws JspTagException when {@code type} is none of the three, or the pattern is invalid
     */
    static NumberFormat numberFormat(String action, String type, String pattern, Locale locale)
            throws JspTagException {
        NumberFormat format = switch (type == null || type.isEmpty() ? "number" : type) {
            case "number" -> NumberFormat.getNumberInstance(locale);
            case "currency" -> NumberFormat.getCurrencyInstance(locale);
            case "percent" -> NumberFormat.getPercentInstance(locale);
            default -> throw new JspTagException(action + ": invalid type '" + type + "'");
        };
        if (pattern != null && !pattern.isEmpty() && format instanceof DecimalFormat decimal) {
            try {
                decimal.applyPattern(pattern);
            } catch (IllegalArgumentException e) {
                throw new JspTagException(action + ": invalid pattern '" + pattern + "'", e);
            }
        }
        return format;
    }

    /** A {@code String} id or a {@link TimeZone}; {@code null} for anything else, or an empty id. */
    static TimeZone convert(Object value) {
        if (value instanceof TimeZone zone) {
            return zone;
        }
        if (value instanceof String id && !id.isEmpty()) {
            return TimeZone.getTimeZone(id);
        }
        return null;
    }
}
