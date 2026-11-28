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

import java.util.Locale;

/** Locale text and configuration values to {@link Locale} (Tags 3.0 §8.5). */
public final class Locales {

    private Locales() {
    }

    /**
     * Parses {@code language}, {@code language-COUNTRY} or {@code language_COUNTRY}.
     *
     * @param text    the locale text, possibly {@code null} or empty
     * @param variant an optional variant, {@code null} or empty for none
     * @return the locale, or {@code null} when {@code text} is {@code null} or empty
     * @throws IllegalArgumentException when the text is not a well-formed locale
     */
    public static Locale parse(String text, String variant) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String language = text;
        String country = "";
        int sep = firstSeparator(text);
        if (sep >= 0) {
            language = text.substring(0, sep);
            country = text.substring(sep + 1);
        }
        if (!isAlpha(language, 2, 3) || !(country.isEmpty() || isAlpha(country, 2, 2))) {
            throw new IllegalArgumentException("Invalid locale: '" + text + "'");
        }
        return Locale.of(language.toLowerCase(Locale.ROOT), country.toUpperCase(Locale.ROOT),
                variant == null ? "" : variant);
    }

    /**
     * Converts a configuration value: a {@link Locale} as is, a {@link String} parsed.
     *
     * @throws IllegalArgumentException for any other type or a malformed string
     */
    public static Locale of(Object configValue) {
        return switch (configValue) {
            case null -> null;
            case Locale locale -> locale;
            case String text -> parse(text, null);
            default -> throw new IllegalArgumentException(
                    "Not a locale: " + configValue.getClass().getName());
        };
    }

    private static int firstSeparator(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '-' || c == '_') {
                return i;
            }
        }
        return -1;
    }

    private static boolean isAlpha(String s, int min, int max) {
        if (s.length() < min || s.length() > max) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) {
                return false;
            }
        }
        return true;
    }
}
