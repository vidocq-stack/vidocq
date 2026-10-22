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

package io.vidocq.bodoni.core.functions;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Map;
import java.util.StringTokenizer;

import io.vidocq.bodoni.core.internal.Xml;

/**
 * The functions of the {@code fn:} tag library (Tags 3.0 section 15). Every
 * {@code String} argument that is {@code null} is processed as the empty string.
 */
public final class Functions {

    private Functions() {
    }

    /** {@code fn:contains}. */
    public static boolean contains(String string, String substring) {
        return nn(string).contains(nn(substring));
    }

    /** {@code fn:containsIgnoreCase}. */
    public static boolean containsIgnoreCase(String string, String substring) {
        return nn(string).toUpperCase().contains(nn(substring).toUpperCase());
    }

    /** {@code fn:endsWith}. */
    public static boolean endsWith(String string, String suffix) {
        return nn(string).endsWith(nn(suffix));
    }

    /** {@code fn:escapeXml}. */
    public static String escapeXml(String string) {
        return Xml.escape(string);
    }

    /** {@code fn:indexOf}. */
    public static int indexOf(String string, String substring) {
        return nn(string).indexOf(nn(substring));
    }

    /** {@code fn:join}. */
    public static String join(String[] array, String separator) {
        if (array == null) {
            return "";
        }
        return String.join(nn(separator), array);
    }

    /**
     * {@code fn:length}: number of items of a collection (any type accepted by the
     * {@code items} attribute of {@code <c:forEach>}) or characters of a string.
     */
    public static int length(Object input) {
        if (input == null) {
            return 0;
        }
        if (input instanceof CharSequence s) {
            return s.length();
        }
        if (input instanceof Collection<?> c) {
            return c.size();
        }
        if (input instanceof Map<?, ?> m) {
            return m.size();
        }
        if (input.getClass().isArray()) {
            return Array.getLength(input);
        }
        if (input instanceof Iterator<?> it) {
            int n = 0;
            while (it.hasNext()) {
                it.next();
                n++;
            }
            return n;
        }
        if (input instanceof Enumeration<?> en) {
            int n = 0;
            while (en.hasMoreElements()) {
                en.nextElement();
                n++;
            }
            return n;
        }
        throw new IllegalArgumentException("Illegal type for fn:length: " + input.getClass().getName());
    }

    /** {@code fn:replace}. */
    public static String replace(String input, String before, String after) {
        String in = nn(input);
        String b = nn(before);
        if (b.isEmpty()) {
            return in;
        }
        return in.replace(b, nn(after));
    }

    /** {@code fn:split}, with the semantics of {@link StringTokenizer}. */
    public static String[] split(String string, String delimiters) {
        String in = nn(string);
        String delims = nn(delimiters);
        if (in.isEmpty() || delims.isEmpty()) {
            return new String[] {in};
        }
        StringTokenizer tokenizer = new StringTokenizer(in, delims);
        String[] tokens = new String[tokenizer.countTokens()];
        for (int i = 0; i < tokens.length; i++) {
            tokens[i] = tokenizer.nextToken();
        }
        return tokens;
    }

    /** {@code fn:startsWith}. */
    public static boolean startsWith(String string, String prefix) {
        return nn(string).startsWith(nn(prefix));
    }

    /** {@code fn:substring}. */
    public static String substring(String string, int beginIndex, int endIndex) {
        String in = nn(string);
        int length = in.length();
        int begin = Math.max(beginIndex, 0);
        int end = endIndex < 0 || endIndex > length ? length : endIndex;
        if (begin >= length || end < begin) {
            return "";
        }
        return in.substring(begin, end);
    }

    /** {@code fn:substringAfter}. */
    public static String substringAfter(String string, String substring) {
        String in = nn(string);
        String sub = nn(substring);
        int i = in.indexOf(sub);
        return i < 0 ? "" : in.substring(i + sub.length());
    }

    /** {@code fn:substringBefore}. */
    public static String substringBefore(String string, String substring) {
        String in = nn(string);
        int i = in.indexOf(nn(substring));
        return i < 0 ? "" : in.substring(0, i);
    }

    /** {@code fn:toLowerCase}. */
    public static String toLowerCase(String string) {
        return nn(string).toLowerCase();
    }

    /** {@code fn:toUpperCase}. */
    public static String toUpperCase(String string) {
        return nn(string).toUpperCase();
    }

    /** {@code fn:trim}. */
    public static String trim(String string) {
        return nn(string).trim();
    }

    private static String nn(String s) {
        return s == null ? "" : s;
    }
}
