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
package io.vidocq.runtime.tooling;

import java.util.ArrayList;
import java.util.List;

/**
 * A unified diff of two texts, line by line, as {@code diff -u} prints one: what {@code vidocq pom convert} shows
 * before it writes (Vidocq/vidocq#380). Three lines of context around each change; the empty string for equal texts.
 */
public final class TextDiff {

    private static final int CONTEXT = 3;

    private TextDiff() {}

    /** The unified diff from {@code before} to {@code after}, or {@code ""} when they are equal. */
    public static String unified(String before, String after) {
        if (before.equals(after)) {
            return "";
        }
        List<String> a = before.lines().toList();
        List<String> b = after.lines().toList();
        List<Edit> edits = edits(a, b);
        StringBuilder out = new StringBuilder("--- before\n+++ after\n");
        int i = 0;
        while (i < edits.size()) {
            // one hunk: from the first change back by CONTEXT lines to the last change of a run that stays within
            // 2 * CONTEXT lines of the next one
            int firstChange = nextChange(edits, i);
            if (firstChange < 0) {
                break;
            }
            int start = Math.max(0, firstChange - CONTEXT);
            int end = firstChange;
            for (int next = nextChange(edits, end + 1); next >= 0 && next - end <= 2 * CONTEXT;
                 next = nextChange(edits, end + 1)) {
                end = next;
            }
            end = Math.min(edits.size() - 1, end + CONTEXT);
            hunk(out, edits, start, end);
            i = end + 1;
        }
        return out.toString();
    }

    private static void hunk(StringBuilder out, List<Edit> edits, int start, int end) {
        int aStart = -1;
        int bStart = -1;
        int aCount = 0;
        int bCount = 0;
        for (int k = start; k <= end; k++) {
            Edit e = edits.get(k);
            if (e.kind() != '+') {
                aCount++;
                if (aStart < 0) {
                    aStart = e.aLine();
                }
            }
            if (e.kind() != '-') {
                bCount++;
                if (bStart < 0) {
                    bStart = e.bLine();
                }
            }
        }
        out.append("@@ -").append(aStart < 0 ? 0 : aStart).append(',').append(aCount)
                .append(" +").append(bStart < 0 ? 0 : bStart).append(',').append(bCount).append(" @@\n");
        for (int k = start; k <= end; k++) {
            out.append(edits.get(k).kind()).append(edits.get(k).text()).append('\n');
        }
    }

    private static int nextChange(List<Edit> edits, int from) {
        for (int k = from; k < edits.size(); k++) {
            if (edits.get(k).kind() != ' ') {
                return k;
            }
        }
        return -1;
    }

    /** One line of the diff: kept ({@code ' '}), removed ({@code '-'}) or added ({@code '+'}); lines are 1-based. */
    private record Edit(char kind, String text, int aLine, int bLine) {}

    /** The edit script of the longest common subsequence of {@code a} and {@code b}. */
    private static List<Edit> edits(List<String> a, List<String> b) {
        int[][] lcs = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Edit> edits = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.size() || j < b.size()) {
            if (i < a.size() && j < b.size() && a.get(i).equals(b.get(j))) {
                edits.add(new Edit(' ', a.get(i), i + 1, j + 1));
                i++;
                j++;
            } else if (j < b.size() && (i >= a.size() || lcs[i][j + 1] >= lcs[i + 1][j])) {
                edits.add(new Edit('+', b.get(j), i + 1, j + 1));
                j++;
            } else {
                edits.add(new Edit('-', a.get(i), i + 1, j + 1));
                i++;
            }
        }
        return edits;
    }
}
