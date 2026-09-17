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
package io.vidocq.runtime.core.banner;

import java.util.function.Function;

/**
 * The placeholders of a custom banner: <code>${key}</code> and <code>${key:default}</code>.
 *
 * <ul>
 *   <li>A key without a value gives its default, or nothing.</li>
 *   <li>A placeholder closes on its own line: an unterminated <code>${</code> is copied as is.</li>
 *   <li>Nothing else is interpreted: the lines of the file are neither cut nor checked.</li>
 * </ul>
 */
final class BannerTemplate {

    private BannerTemplate() {}

    /** Replaces every placeholder with {@code values.apply(key)}, its default, or nothing. */
    static String resolve(String template, Function<String, String> values) {
        StringBuilder out = new StringBuilder(template.length());
        int i = 0;
        while (i < template.length()) {
            int start = template.indexOf("${", i);
            if (start < 0) {
                out.append(template, i, template.length());
                break;
            }
            int end = closing(template, start);
            if (end < 0) {
                out.append(template, i, start + 2);
                i = start + 2;
                continue;
            }
            out.append(template, i, start);
            String expression = template.substring(start + 2, end);
            int colon = expression.indexOf(':');
            String key = (colon < 0 ? expression : expression.substring(0, colon)).strip();
            String value = key.isEmpty() ? null : values.apply(key);
            if (value != null) {
                out.append(value);
            } else if (colon >= 0) {
                out.append(expression, colon + 1, expression.length());
            }
            i = end + 1;
        }
        return out.toString();
    }

    /** Whether {@code template} holds a <code>${key}</code> or <code>${key:default}</code> placeholder. */
    static boolean references(String template, String key) {
        boolean[] found = {false};
        resolve(template, k -> {
            if (k.equals(key)) {
                found[0] = true;
            }
            return null;
        });
        return found[0];
    }

    /** The closing brace of the placeholder opened at {@code start}, or -1 when its line ends first. */
    private static int closing(String template, int start) {
        for (int j = start + 2; j < template.length(); j++) {
            char c = template.charAt(j);
            if (c == '}') {
                return j;
            }
            if (c == '\n' || c == '\r') {
                return -1;
            }
        }
        return -1;
    }
}
