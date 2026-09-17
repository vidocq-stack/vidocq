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
package io.vidocq.runtime.maven.idea;

import java.util.ArrayList;
import java.util.List;

/**
 * A whole-text line diff, longest common subsequence based. Run configurations are about a dozen lines,
 * so every line is printed: two spaces for a common line, {@code "- "} for a line only on disk,
 * {@code "+ "} for a line only expected.
 */
final class LineDiff {

    private LineDiff() {
    }

    static List<String> diff(String onDisk, String expected) {
        String[] a = lines(onDisk);
        String[] b = lines(expected);
        int[][] common = new int[a.length + 1][b.length + 1];
        for (int i = a.length - 1; i >= 0; i--) {
            for (int j = b.length - 1; j >= 0; j--) {
                common[i][j] = a[i].equals(b[j])
                        ? common[i + 1][j + 1] + 1
                        : Math.max(common[i + 1][j], common[i][j + 1]);
            }
        }
        List<String> out = new ArrayList<>(a.length + b.length);
        int i = 0;
        int j = 0;
        while (i < a.length && j < b.length) {
            if (a[i].equals(b[j])) {
                out.add("  " + a[i]);
                i++;
                j++;
            } else if (common[i + 1][j] >= common[i][j + 1]) {
                out.add("- " + a[i++]);
            } else {
                out.add("+ " + b[j++]);
            }
        }
        while (i < a.length) {
            out.add("- " + a[i++]);
        }
        while (j < b.length) {
            out.add("+ " + b[j++]);
        }
        return out;
    }

    private static String[] lines(String text) {
        return text.isEmpty() ? new String[0] : text.split("\n", -1);
    }
}
