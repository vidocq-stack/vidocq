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
package io.vidocq.runtime.extensions.essentials.devconsole;

/**
 * The text the console shows, cleaned and cut to the limits of the startup report: every string a panel or the
 * report hands it goes through {@link #clean(String)} before it reaches the snapshot.
 */
final class Texts {

    /** The longest value shown, as in the startup report. */
    static final int MAX_VALUE = 200;

    /** What ends a value that was cut. */
    private static final String CUT = "...";
    /** What stands for a character that could break a line or mislead a reader. */
    private static final char REPLACEMENT = '?';

    private Texts() {}

    /**
     * {@code value} safe to show on one line: every control character (line breaks, {@code ESC}, C1 controls),
     * every invisible formatting character (bidirectional overrides) and the Unicode line and paragraph separators
     * replaced by {@code ?}, then cut to {@value #MAX_VALUE} characters, the last three being {@code ...}, as the
     * report cleans its own values.
     *
     * @param value the text, or {@code null}
     * @return the clean text, or {@code null} for {@code null}
     */
    static String clean(String value) {
        return clean(value, MAX_VALUE, false);
    }

    /**
     * {@code value} cleaned as {@link #clean(String)} does, cut to {@code max} characters instead.
     *
     * @param value the text, or {@code null}
     * @param max   the most characters kept, the last three being {@code ...} when it is cut
     * @return the clean text, or {@code null} for {@code null}
     */
    static String clean(String value, int max) {
        return clean(value, max, false);
    }

    /**
     * {@code value} safe to show as a block of lines, such as an action's description: cleaned as
     * {@link #clean(String, int)}, but a line feed and a tab are kept and a carriage return is dropped.
     *
     * @param value the text, or {@code null}
     * @param max   the most characters kept
     * @return the clean text, or {@code null} for {@code null}
     */
    static String block(String value, int max) {
        return clean(value, max, true);
    }

    private static String clean(String value, int max, boolean lines) {
        if (value == null) {
            return null;
        }
        StringBuilder cleaned = new StringBuilder(Math.min(value.length(), max + 1));
        for (int i = 0; i < value.length() && cleaned.length() <= max; ) {
            int codePoint = value.codePointAt(i);
            i += Character.charCount(codePoint);
            if (lines && codePoint == '\r') {
                continue;
            }
            if (lines && (codePoint == '\n' || codePoint == '\t')) {
                cleaned.appendCodePoint(codePoint);
            } else if (unsafe(codePoint)) {
                cleaned.append(REPLACEMENT);
            } else {
                cleaned.appendCodePoint(codePoint);
            }
        }
        if (cleaned.length() <= max) {
            return cleaned.toString();
        }
        int end = max - CUT.length();
        if (Character.isLowSurrogate(cleaned.charAt(end))) {
            end--;
        }
        return cleaned.substring(0, end) + CUT;
    }

    private static boolean unsafe(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint) || type == Character.FORMAT || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }
}
