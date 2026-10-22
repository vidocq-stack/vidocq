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

package io.vidocq.bodoni.core.internal;

import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.jsp.PageContext;
import jakarta.servlet.jsp.jstl.core.Config;
import jakarta.servlet.jsp.jstl.fmt.LocalizationContext;

/**
 * The internationalisation algorithms shared by the {@code fmt:} tags: preferred locales
 * (Tags 3.0 §8.2.1), resource bundle determination (§8.3), response locale and encoding
 * (§8.4) and formatting locale lookup (§9.3).
 */
public final class I18n {

    /** Session attribute holding the response character encoding (§8.4). */
    public static final String REQUEST_CHARSET = "jakarta.servlet.jsp.jstl.fmt.request.charset";

    private static final String ACCEPT_LANGUAGE = "Accept-Language";

    /** Locales supported by {@link NumberFormat}. */
    public static final Locale[] NUMBER_LOCALES = NumberFormat.getAvailableLocales();

    /** Locales supported by {@link DateFormat}. */
    public static final Locale[] DATE_LOCALES = DateFormat.getAvailableLocales();

    /** Locales supported by both number and date formatting (§8.8). */
    public static final Locale[] MESSAGE_LOCALES = intersection(NUMBER_LOCALES, DATE_LOCALES);

    private I18n() {
    }

    /**
     * The preferred locales of a page (§8.2.1): the configured locale when set
     * (application-based), else the request's locales in order (browser-based). An HTTP
     * request without an {@code Accept-Language} header has no browser-based locales: the
     * container then answers {@code getLocales()} with its own default locale, and the
     * algorithms of §8.3.2 and §9.3 never consider the default locale of the runtime.
     *
     * @throws IllegalArgumentException when the configured locale is malformed
     */
    public static List<Locale> preferredLocales(PageContext pc) {
        Locale configured = Locales.of(Config.find(pc, Config.FMT_LOCALE));
        if (configured != null) {
            return List.of(configured);
        }
        ServletRequest request = pc.getRequest();
        if (request instanceof HttpServletRequest http && isBlank(http.getHeader(ACCEPT_LANGUAGE))) {
            return List.of();
        }
        return Collections.list(request.getLocales());
    }

    /**
     * First-level bundle lookup (§8.3.1) for one locale, without the JVM default locale
     * and without the root bundle.
     *
     * @return the bundle, or {@code null} when none matches or only the root bundle does
     */
    public static ResourceBundle lookup(String basename, Locale locale) {
        ResourceBundle bundle = load(basename, locale);
        return bundle == null || bundle.getLocale().getLanguage().isEmpty() ? null : bundle;
    }

    /**
     * Determines the localization context for a basename (§8.3.2).
     *
     * @return a context holding the bundle and the preferred (or fallback) locale that led
     *         to it, the root bundle without locale, or an empty context
     * @throws IllegalArgumentException when the configured locale or fallback locale is malformed
     */
    public static LocalizationContext determine(PageContext pc, String basename) {
        if (basename == null || basename.isEmpty()) {
            return new LocalizationContext();
        }
        List<Locale> candidates = new ArrayList<>(preferredLocales(pc));
        Locale fallback = Locales.of(Config.find(pc, Config.FMT_FALLBACK_LOCALE));
        if (fallback != null) {
            candidates.add(fallback);
        }
        for (Locale candidate : candidates) {
            ResourceBundle bundle = lookup(basename, candidate);
            if (bundle != null) {
                return new LocalizationContext(bundle, candidate);
            }
        }
        ResourceBundle root = load(basename, Locale.ROOT);
        return root == null ? new LocalizationContext() : new LocalizationContext(root, null);
    }

    /**
     * Sets the response locale and, if a session exists and has not been invalidated, records
     * the resulting response character encoding in it (§8.4). Never creates a session.
     */
    public static void setResponseLocale(PageContext pc, Locale locale) {
        if (locale == null) {
            return;
        }
        pc.getResponse().setLocale(locale);
        if (pc.getRequest() instanceof HttpServletRequest http) {
            HttpSession session = http.getSession(false);
            if (session != null) {
                try {
                    session.setAttribute(REQUEST_CHARSET, pc.getResponse().getCharacterEncoding());
                } catch (IllegalStateException _) {
                    // invalidated session: behave as if there were none
                }
            }
        }
    }

    /**
     * Finds the formatting locale (§9.3.3): the first preferred locale, then the fallback
     * locale, that matches an available locale.
     *
     * @return the matching available locale, or {@code null}
     * @throws IllegalArgumentException when the configured locale or fallback locale is malformed
     */
    public static Locale formattingLocale(PageContext pc, Locale[] available) {
        List<Locale> candidates = new ArrayList<>(preferredLocales(pc));
        Locale fallback = Locales.of(Config.find(pc, Config.FMT_FALLBACK_LOCALE));
        if (fallback != null) {
            candidates.add(fallback);
        }
        for (Locale candidate : candidates) {
            Locale match = match(candidate, available);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    /** §9.3.2 match order: language, country and variant; language and country; language alone. */
    private static Locale match(Locale wanted, Locale[] available) {
        for (Locale a : available) {
            if (a.equals(wanted)) {
                return a;
            }
        }
        if (!wanted.getCountry().isEmpty()) {
            for (Locale a : available) {
                if (a.getLanguage().equals(wanted.getLanguage())
                        && a.getCountry().equals(wanted.getCountry())) {
                    return a;
                }
            }
        }
        for (Locale a : available) {
            if (a.getLanguage().equals(wanted.getLanguage()) && a.getCountry().isEmpty()) {
                return a;
            }
        }
        return null;
    }

    /**
     * Loads a bundle through the context class loader without ever falling back to the JVM
     * default locale. {@code ResourceBundle.Control} (and its no-fallback variant) is not
     * supported for callers in a named module, so a bundle that the JDK took from the
     * default locale's candidate chain is recognised and discarded instead.
     */
    private static ResourceBundle load(String basename, Locale locale) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = ClassLoader.getSystemClassLoader();
        }
        ResourceBundle bundle;
        try {
            bundle = ResourceBundle.getBundle(basename, locale, loader);
        } catch (MissingResourceException _) {
            return null;
        }
        return inCandidateChain(bundle.getLocale(), locale) ? bundle : null;
    }

    /** Whether {@code found} is the root locale or a prefix of {@code wanted}'s candidate chain. */
    private static boolean inCandidateChain(Locale found, Locale wanted) {
        if (found.getLanguage().isEmpty()) {
            return true;
        }
        return found.getLanguage().equals(wanted.getLanguage())
                && (found.getCountry().isEmpty() || found.getCountry().equals(wanted.getCountry()))
                && (found.getVariant().isEmpty() || found.getVariant().equals(wanted.getVariant()));
    }

    /** The locales of {@code first} that {@code second} also holds; either may repeat a locale. */
    static Locale[] intersection(Locale[] first, Locale[] second) {
        Set<Locale> other = new HashSet<>(Arrays.asList(second));
        return Arrays.stream(first).filter(other::contains).distinct().toArray(Locale[]::new);
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
