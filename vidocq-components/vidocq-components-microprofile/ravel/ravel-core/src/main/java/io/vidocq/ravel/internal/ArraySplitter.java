/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * MP Config 3.1 §5.4 — découpage d'une chaîne brute en éléments par séparateur
 * virgule, avec échappement {@code \,}.
 *
 * <p>Règles spec :</p>
 * <ul>
 *   <li>Le séparateur est la virgule {@code ,}.</li>
 *   <li>Une virgule littérale s'échappe avec {@code \,}.</li>
 *   <li>Les éléments vides (résultat du split) sont <b>ignorés</b>.</li>
 *   <li>Toute autre séquence d'échappement {@code \X} est conservée verbatim
 *       (la spec ne définit pas {@code \\} ; un anti-slash isolé en fin de chaîne
 *       est conservé tel quel).</li>
 * </ul>
 */
final class ArraySplitter {

    private ArraySplitter() {
        // utilitaire
    }

    /** Découpe la chaîne brute en éléments, en honorant {@code \,}. */
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

