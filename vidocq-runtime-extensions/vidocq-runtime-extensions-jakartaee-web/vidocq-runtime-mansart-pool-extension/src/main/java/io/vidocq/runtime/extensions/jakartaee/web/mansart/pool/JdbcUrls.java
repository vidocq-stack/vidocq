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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool;

import java.util.List;
import java.util.Locale;

/**
 * A JDBC URL as the startup report, the dev console and an error message may show it: without the credentials it can
 * carry, and never more than its sub-protocol when it cannot be read.
 *
 * <p>A driver takes credentials in several places, and each is removed:
 * <ol>
 *   <li><b>User info</b>, looked for only before the first {@code ?} or {@code ;}, since a parameter may hold an
 *       {@code @}, such as an e-mail address: everything from where the shape of the URL starts it through the last
 *       {@code @}, whatever it holds in between. It starts after the {@code //} that opens the authority, as in
 *       {@code jdbc:mysql://app:secret@host/db}, when nothing but the sub-protocol and colon-separated names precede
 *       that {@code //}, as in {@code jdbc:h2:tcp://}; otherwise the URL has the Oracle form, {@code user/password}
 *       after the colon that opens it, as in {@code jdbc:oracle:thin:scott/tiger@host:1521:orcl}, which keeps
 *       {@code jdbc:oracle:thin:@host:1521:orcl}. A password may hold a {@code /}, a {@code //}, a {@code +} or an
 *       {@code =}, as a base64 one does: {@code jdbc:oracle:thin:scott/Xy7+//Qp4==@host:1521:orcl} keeps
 *       {@code jdbc:oracle:thin:@host:1521:orcl} too. So an {@code @} in a path is taken for the end of the user
 *       info as well: {@code jdbc:postgresql://host/db@x} shows as {@code jdbc:postgresql://x}, more hidden than
 *       needed, never less.</li>
 *   <li><b>Parameters and settings</b>: every {@code ?}/{@code &} parameter and every {@code ;} setting whose key
 *       holds, ignoring case, {@code password}, {@code passwd}, {@code pwd}, {@code secret}, {@code token} or
 *       {@code credential}, such as {@code ?sslpassword=} or H2's {@code ;PASSWORD=}. A value in braces, SQL
 *       Server's {@code {a;b}}, is one value.</li>
 * </ol>
 *
 * <p>A password may also hold a {@code ?} or a {@code ;}, which MySQL and Oracle accept in user info, as in
 * {@code jdbc:mysql://app:pa;ss@host/db}: its {@code @} then lies past the part user info is looked for in, and what
 * precedes the {@code ;} would be shown. So an {@code @} past that part is allowed in two places only, the value of a
 * parameter or setting whose key holds {@code user} or {@code mail}, ignoring case, such as
 * {@code ?user=app@example.com} or Azure SQL's {@code ;user=admin@server}, and the value of a secret one, which is
 * removed whole, such as {@code ;password=P@ss}; anywhere else, in a key, in an entry with no {@code =}, secret-named
 * or not, or in the value of any other kept entry, the URL fails closed. What still gets through is a password that
 * itself holds {@code ;user=}, {@code ;pwd=} or the like before its {@code @}.
 *
 * <p>It never throws. A URL it cannot read, one with unbalanced braces, one with an {@code @} past the user info part
 * that is not in a user or e-mail value, one with an {@code @} before where its user info starts, which leaves no
 * telling which {@code @} ends it, or one whose part before the first {@code ?} or {@code ;} still holds a secret
 * key after the user info is gone, as MySQL's {@code address=(password=…)} would, is shown as
 * {@code jdbc:<sub-protocol>:…}: fail closed.
 *
 * <p>Package-private for now: it moves to the SPI when a second extension needs it.
 */
final class JdbcUrls {

    /** What a URL shows once nothing can be read: its sub-protocol, when it has a readable one, then an ellipsis. */
    private static final String ELLIPSIS = "…";

    /** What a parameter or setting key holds, ignoring case, when its value is a credential. */
    private static final List<String> SECRET_KEYS =
            List.of("password", "passwd", "pwd", "secret", "token", "credential");

    /**
     * What a parameter or setting key holds, ignoring case, when its value may hold an {@code @} that closes no user
     * info: a user name or an e-mail address.
     */
    private static final List<String> AT_VALUE_KEYS = List.of("user", "mail");

    /**
     * A URL redacted.
     *
     * @param url                the URL without its credentials, or {@code jdbc:<sub-protocol>:…}
     * @param credentialsRemoved whether user info, a parameter or a setting was removed from it; {@code false} when the
     *                           URL could not be read
     */
    record Redacted(String url, boolean credentialsRemoved) {}

    private JdbcUrls() {
    }

    /**
     * The URL without its credentials.
     *
     * @param jdbcUrl the URL as it is configured, or {@code null}
     * @return what may be shown of it, never {@code null}
     */
    static String redact(String jdbcUrl) {
        return redacted(jdbcUrl).url();
    }

    /**
     * The URL without its credentials, and whether it had any.
     *
     * @param jdbcUrl the URL as it is configured, or {@code null}
     * @return the redacted URL, never {@code null}
     */
    static Redacted redacted(String jdbcUrl) {
        try {
            Redacted redacted = redactOrNull(jdbcUrl);
            return redacted != null ? redacted : new Redacted(failClosed(jdbcUrl), false);
        } catch (RuntimeException unreadable) {
            return new Redacted(failClosed(jdbcUrl), false);
        }
    }

    /**
     * What database the URL reaches, for a report that shows no URL: its sub-protocol, such as {@code postgresql},
     * and for H2 how it is reached, {@code h2 mem}, {@code h2 file}, {@code h2 tcp} or {@code h2 ssl}.
     *
     * @param jdbcUrl the URL, or {@code null}
     * @return the kind, {@code unknown} when the URL has no readable sub-protocol
     */
    static String kind(String jdbcUrl) {
        String sub = subProtocol(jdbcUrl);
        if (sub == null) {
            return "unknown";
        }
        sub = sub.toLowerCase(Locale.ROOT);
        if (!sub.equals("h2")) {
            return sub;
        }
        String rest = jdbcUrl.substring("jdbc:h2:".length()).toLowerCase(Locale.ROOT);
        for (String reached : List.of("mem", "tcp", "ssl")) {
            if (rest.startsWith(reached + ":")) {
                return "h2 " + reached;
            }
        }
        return "h2 file";
    }

    /**
     * The host and port of a server URL, the authority after {@code //} without its user info, such as
     * {@code localhost:54213}. It is read from the {@linkplain #redacted redacted} URL, so it is never user info the
     * redactor could not remove.
     *
     * @param jdbcUrl the URL, or {@code null}
     * @return the authority, or {@code null} when the URL has none or cannot be read
     */
    static String authority(String jdbcUrl) {
        Redacted redacted;
        try {
            redacted = redactOrNull(jdbcUrl);
        } catch (RuntimeException unreadable) {
            return null;
        }
        if (redacted == null) {
            return null;
        }
        String url = redacted.url();
        int slashes = url.indexOf("//");
        if (slashes < 0) {
            return null;
        }
        int start = slashes + 2;
        int end = start;
        while (end < url.length() && "/?;".indexOf(url.charAt(end)) < 0) {
            end++;
        }
        String authority = url.substring(start, end);
        authority = authority.substring(authority.lastIndexOf('@') + 1);
        return authority.isEmpty() ? null : authority;
    }

    /** The URL redacted, or {@code null} when it cannot be read. */
    private static Redacted redactOrNull(String url) {
        String sub = subProtocol(url);
        if (sub == null) {
            return null;
        }
        int subEnd = "jdbc:".length() + sub.length();
        int headEnd = firstOf(url, "?;", subEnd);
        String head = url.substring(0, headEnd);
        boolean removed = false;

        int at = head.lastIndexOf('@');
        if (at >= 0) {
            int slashes = authorityOpener(head, subEnd);
            if (slashes >= 0 && slashes < at) {
                head = head.substring(0, slashes + 2) + head.substring(at + 1);
                removed = true;
            } else {
                // Oracle: [user[/password]]@ opened by a colon; a password may hold a colon, not the user
                int slash = head.indexOf('/', subEnd);
                int colon = head.lastIndexOf(':', slash >= 0 && slash < at ? slash : at);
                if (head.indexOf('@', subEnd) < colon) {
                    return null;
                }
                removed = at > colon + 1;
                head = head.substring(0, colon + 1) + head.substring(at);
            }
        }
        String lowerHead = head.toLowerCase(Locale.ROOT);
        for (String key : SECRET_KEYS) {
            if (lowerHead.contains(key + "=")) {
                return null;
            }
        }

        StringBuilder out = new StringBuilder(head);
        boolean queryOpened = false;
        int start = headEnd;
        while (start < url.length()) {
            char separator = url.charAt(start);
            int end = entryEnd(url, start + 1);
            if (end < 0) {
                return null;
            }
            String entry = url.substring(start + 1, end);
            if (separator == '?') {
                queryOpened = true;
            }
            if (atOutsideValue(entry)) {
                // before isSecret: removing pwd@host alone, as a secret-named entry, would show the pa of pa;pwd@host
                return null;
            } else if (isSecret(entry)) {
                removed = true;
            } else if (mayCloseUserInfo(entry)) {
                return null;
            } else {
                if (queryOpened && (separator == '?' || separator == '&')) {
                    // the first parameter kept opens the query, even when the one that opened it was removed
                    separator = out.indexOf("?", head.length()) < 0 ? '?' : '&';
                }
                out.append(separator).append(entry);
            }
            start = end;
        }
        return new Redacted(out.toString(), removed);
    }

    /**
     * Where the {@code //} that opens the authority of a URL starts, as in {@code jdbc:mysql://} or
     * {@code jdbc:h2:tcp://}: the first {@code //} after the sub-protocol, when a colon comes right before it and
     * only names, letters, digits, {@code .}, {@code _} or {@code -} separated by colons, lie between the two. Any
     * other {@code //} is part of what follows the opener, such as the password of {@code scott/Xy7+//Qp4==}.
     *
     * @param head   the URL before its first {@code ?} or {@code ;}
     * @param subEnd the index of the colon that ends the sub-protocol
     * @return the index, or {@code -1} when the URL opens no authority
     */
    private static int authorityOpener(String head, int subEnd) {
        int slashes = head.indexOf("//", subEnd);
        if (slashes < 0 || head.charAt(slashes - 1) != ':') {
            return -1;
        }
        for (int i = subEnd; i < slashes; i++) {
            char c = head.charAt(i);
            if (!(c < 128 && (Character.isLetterOrDigit(c) || ":._-".indexOf(c) >= 0))) {
                return -1;
            }
        }
        return slashes;
    }

    /**
     * Where the parameter or setting that starts at {@code from} ends: the next {@code ?}, {@code &} or {@code ;}
     * outside braces, or the end of the URL.
     *
     * @return the index, or {@code -1} when a brace is not closed
     */
    private static int entryEnd(String url, int from) {
        int depth = 0;
        for (int i = from; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth < 0) {
                    return -1;
                }
            } else if (depth == 0 && "?&;".indexOf(c) >= 0) {
                return i;
            }
        }
        return depth == 0 ? url.length() : -1;
    }

    /** Whether the key of a {@code key=value} entry, or the whole entry when it has no {@code =}, names a credential. */
    private static boolean isSecret(String entry) {
        int equals = entry.indexOf('=');
        String key = (equals < 0 ? entry : entry.substring(0, equals)).toLowerCase(Locale.ROOT);
        for (String secret : SECRET_KEYS) {
            if (key.contains(secret)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether an entry holds an {@code @} outside any value: in an entry with no {@code =}, or before its {@code =}.
     * No key holds one, so it may close user info whose password holds a {@code ?} or a {@code ;}, even when the
     * entry reads as a secret one, as {@code pwd@host/db} in {@code app:pa;pwd@host/db}.
     */
    private static boolean atOutsideValue(String entry) {
        int at = entry.indexOf('@');
        if (at < 0) {
            return false;
        }
        int equals = entry.indexOf('=');
        return equals < 0 || at < equals;
    }

    /**
     * Whether a {@code key=value} entry that is kept, one with no {@code @} {@linkplain #atOutsideValue outside its
     * value}, holds an {@code @} that may close user info whose password holds a {@code ?} or a {@code ;}: an
     * {@code @} in the value of any entry but a user or e-mail one, such as {@code user=app@example.com}.
     * {@code x=1@host/db}, what follows the {@code ;} of {@code app:pa;x=1@host/db}, is one.
     */
    private static boolean mayCloseUserInfo(String entry) {
        if (entry.indexOf('@') < 0) {
            return false;
        }
        String key = entry.substring(0, entry.indexOf('=')).toLowerCase(Locale.ROOT);
        for (String atValue : AT_VALUE_KEYS) {
            if (key.contains(atValue)) {
                return false;
            }
        }
        return true;
    }

    /** The index of the first of {@code chars} in {@code s} from {@code from}, or the length of {@code s}. */
    private static int firstOf(String s, String chars, int from) {
        for (int i = from; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                return i;
            }
        }
        return s.length();
    }

    /**
     * The sub-protocol of a JDBC URL, such as {@code postgresql}: letters, digits, {@code .}, {@code _} or {@code -}
     * between {@code jdbc:} and the next colon.
     *
     * @return the sub-protocol as written, or {@code null} when there is none
     */
    private static String subProtocol(String url) {
        if (url == null || !url.regionMatches(true, 0, "jdbc:", 0, 5)) {
            return null;
        }
        int colon = url.indexOf(':', 5);
        if (colon <= 5) {
            return null;
        }
        String sub = url.substring(5, colon);
        for (int i = 0; i < sub.length(); i++) {
            char c = sub.charAt(i);
            if (!(c < 128 && (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-'))) {
                return null;
            }
        }
        return sub;
    }

    /** What a URL that cannot be read shows: {@code jdbc:<sub-protocol>:…}, or {@code jdbc:…}. */
    private static String failClosed(String url) {
        String sub = subProtocol(url);
        return sub == null ? "jdbc:" + ELLIPSIS : "jdbc:" + sub + ":" + ELLIPSIS;
    }
}
