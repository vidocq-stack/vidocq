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
package io.vidocq.ravel.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * MP Config 3.1 §5.4 string splitting by comma separator with {@code \,} escaping.
 *
 * <p>Rules:</p>
 * <ul>
 *   <li>Separator is comma {@code ,}.</li>
 *   <li>A literal comma is escaped with {@code \,}.</li>
 *   <li>Empty split elements are ignored.</li>
 *   <li>Any other escape sequence {@code \X} is kept verbatim.</li>
 * </ul>
 */
final class ArraySplitter {

    private ArraySplitter() {
        // Utility class
    }

    /** Splits raw text into elements while honoring {@code \,}. */
    static List<String> split(String raw) {
        var out = new ArrayList<String>();
        var sb = new StringBuilder();
        int n = raw.length();
        for (int i = 0; i < n; i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < n && raw.charAt(i + 1) == ',') {
                sb.append(',');
                i++;
            } else if (c == ',') {
                if (!sb.isEmpty()) {
                    out.add(sb.toString());
                    sb.setLength(0);
                }
            } else {
                sb.append(c);
            }
        }
        if (!sb.isEmpty()) {
            out.add(sb.toString());
        }
        return out;
    }
}

