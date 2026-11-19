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

/**
 * XML escaping shared by {@code <c:out>} and {@code fn:escapeXml}
 * (Tags 3.0 section 4.2 and section 15.5).
 */
public final class Xml {

    private Xml() {
    }

    /**
     * Replaces the five characters {@code < > & ' "} by their character entity codes.
     *
     * @param text the text to escape, may be {@code null}
     * @return the escaped text; the empty string when {@code text} is {@code null}
     */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = null;
        int last = 0;
        for (int i = 0; i < text.length(); i++) {
            String entity = switch (text.charAt(i)) {
                case '<' -> "&lt;";
                case '>' -> "&gt;";
                case '&' -> "&amp;";
                case '\'' -> "&#039;";
                case '"' -> "&#034;";
                default -> null;
            };
            if (entity != null) {
                if (out == null) {
                    out = new StringBuilder(text.length() + 16);
                }
                out.append(text, last, i).append(entity);
                last = i + 1;
            }
        }
        if (out == null) {
            return text;
        }
        return out.append(text, last, text.length()).toString();
    }
}
