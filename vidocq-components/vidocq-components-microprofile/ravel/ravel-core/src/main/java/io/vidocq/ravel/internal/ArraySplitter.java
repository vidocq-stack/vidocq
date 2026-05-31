/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
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

