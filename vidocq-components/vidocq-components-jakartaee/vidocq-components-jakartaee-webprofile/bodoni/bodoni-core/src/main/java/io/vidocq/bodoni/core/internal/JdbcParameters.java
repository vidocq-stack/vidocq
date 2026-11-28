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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The JDBC parameters of a string data source, {@code url[,[driver][,[user][,password]]]}
 * (Tags 3.0 §10.1.1, §10.2). References nothing outside {@code java.base}.
 *
 * @param url the JDBC URL
 * @param driver the driver class name, {@code null} when absent or empty
 * @param user the user, {@code ""} when absent
 * @param password the password, {@code ""} when absent
 */
public record JdbcParameters(String url, String driver, String user, String password) {

    private static final char SEPARATOR = ',';
    private static final char ESCAPE = '\\';
    private static final int FIELDS = 4;
    private static final String AUTHORITY = "//";
    private static final String PASSWORD_KEY = "password";
    private static final String PWD_KEY = "pwd";
    private static final Pattern PROPERTY_SEPARATORS = Pattern.compile("[;?&]");

    /**
     * Parses a JDBC parameter string. It splits on unescaped {@code ,} into at most four
     * fields, the fourth (the password) taking the rest of the string, commas included;
     * {@code \,} stands for {@code ,} and {@code \\} for {@code \}, a {@code \} before any
     * other character is kept as is.
     *
     * @throws NullPointerException when {@code parameters} is {@code null}
     */
    public static JdbcParameters parse(String parameters) {
        List<String> fields = new ArrayList<>(FIELDS);
        StringBuilder field = new StringBuilder();
        int length = parameters.length();
        int index = 0;
        while (index < length) {
            char current = parameters.charAt(index);
            if (current == ESCAPE && index + 1 < length && isEscaped(parameters.charAt(index + 1))) {
                field.append(parameters.charAt(index + 1));
                index += 2;
                continue;
            }
            if (current == SEPARATOR && fields.size() < FIELDS - 1) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(current);
            }
            index++;
        }
        fields.add(field.toString());
        String driver = fields.size() > 1 && !fields.get(1).isEmpty() ? fields.get(1) : null;
        String user = fields.size() > 2 ? fields.get(2) : "";
        String password = fields.size() > 3 ? fields.get(3) : "";
        return new JdbcParameters(fields.get(0), driver, user, password);
    }

    /**
     * The name every message uses for a string data source, a JNDI name or a JDBC parameter
     * string: {@code parse(dataSource).redactedUrl()}.
     */
    public static String redact(String dataSource) {
        return parse(dataSource).redactedUrl();
    }

    private static boolean isEscaped(char next) {
        return next == SEPARATOR || next == ESCAPE;
    }

    /**
     * The URL without what may carry a secret: without its user information, then cut at the
     * first {@code ;} or {@code ?} of what follows it. The user information is found on the
     * whole URL, so a password holding {@code ;} or {@code ?} goes entirely, and always ends at
     * the last {@code @}. When a {@code //} comes before that {@code @}, the user information
     * runs from after the {@code //} and the {@code @} goes too ({@code jdbc:x://u:p@h/db}
     * gives {@code jdbc:x://h/db}). Otherwise (the Oracle form) it runs from after the last
     * {@code :} before the first {@code /}, or before the first {@code @} when no {@code /}
     * comes earlier, and the {@code @} stays ({@code jdbc:oracle:thin:scott/tiger@host:1521:db}
     * gives {@code jdbc:oracle:thin:@host:1521:db}). When that {@code @} falls in a property
     * value, whatever its key ({@code jdbc:x://h/db?password=p@ss}, {@code ;token=abc@def}),
     * what follows it is a piece of that value: the URL is then cut where the user information
     * would start ({@code jdbc:x://}). It may cut more than the credentials, never less.
     */
    public String redactedUrl() {
        UserInfo info = userInfo(url);
        if (info == null || propertiesStart(url) < info.start()) {
            // No user information, or what looked like one lies in the properties.
            return withoutUserInfo(beforeProperties());
        }
        String prefix = url.substring(0, info.start());
        if (inProperty(info.at())) {
            return prefix;
        }
        return prefix + withoutProperties(url.substring(info.authority() ? info.at() + 1 : info.at()));
    }

    /** {@code text}, a URL without properties, without its user information. */
    private static String withoutUserInfo(String text) {
        UserInfo info = userInfo(text);
        if (info == null) {
            return text;
        }
        return text.substring(0, info.start()) + text.substring(info.authority() ? info.at() + 1 : info.at());
    }

    private static String withoutProperties(String text) {
        return text.substring(0, propertiesStart(text));
    }

    /** The URL cut at its first {@code ;} or {@code ?}: what a driver may repeat of it alone. */
    String beforeProperties() {
        return withoutProperties(url);
    }

    /**
     * The secrets a driver message might repeat: the password field when not empty, the
     * password of the URL's user information ({@code //user:password@} or
     * {@code user/password@}), read both on the whole URL and on the URL cut at its first
     * {@code ;} or {@code ?}, and the value of every URL property, after that first {@code ;}
     * or {@code ?}, whose key contains {@code password} or {@code pwd} whatever its case; a
     * value in braces counts with and without them. Reading the user information both ways
     * covers a password holding {@code ;}, {@code ?} or {@code @} as well as a property value
     * holding {@code @}: a few more secrets, never fewer.
     */
    Set<String> secrets() {
        Set<String> secrets = new HashSet<>();
        addSecret(secrets, password);
        String cut = beforeProperties();
        addUserInfoSecret(secrets, url);
        addUserInfoSecret(secrets, cut);
        for (String property : PROPERTY_SEPARATORS.split(url.substring(cut.length()), -1)) {
            addPropertySecret(secrets, property);
        }
        return Set.copyOf(secrets);
    }

    private static void addUserInfoSecret(Set<String> secrets, String text) {
        UserInfo info = userInfo(text);
        if (info != null) {
            String userInfo = text.substring(info.start(), info.at());
            int separator = userInfo.indexOf(info.authority() ? ':' : '/');
            if (separator >= 0) {
                addSecret(secrets, userInfo.substring(separator + 1));
            }
        }
    }

    /**
     * Whether the {@code @} at {@code at} falls in a property value, whatever its key: a
     * {@code ;} or {@code ?} comes before it and a {@code =} lies between the two. A password
     * holding {@code ;} or {@code ?} but no {@code =} ({@code u:pa;ss@host}) is user information.
     */
    private boolean inProperty(int at) {
        int start = propertiesStart(url);
        int equals = url.indexOf('=', start);
        return start < at && equals >= 0 && equals < at;
    }

    private static boolean isPasswordKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains(PASSWORD_KEY) || lower.contains(PWD_KEY);
    }

    private static void addPropertySecret(Set<String> secrets, String property) {
        int equals = property.indexOf('=');
        if (equals <= 0 || !isPasswordKey(property.substring(0, equals))) {
            return;
        }
        String value = property.substring(equals + 1);
        addSecret(secrets, value);
        if (value.length() > 2 && value.startsWith("{") && value.endsWith("}")) {
            addSecret(secrets, value.substring(1, value.length() - 1));
        }
    }

    private static void addSecret(Set<String> secrets, String secret) {
        if (secret != null && !secret.isEmpty()) {
            secrets.add(secret);
        }
    }

    /** The index of the first {@code ;} or {@code ?} of {@code text}, else its length. */
    private static int propertiesStart(String text) {
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == ';' || current == '?') {
                return index;
            }
        }
        return text.length();
    }

    /** The user information of a URL, {@code null} when it has none. */
    private static UserInfo userInfo(String cut) {
        int at = cut.lastIndexOf('@');
        if (at < 0) {
            return null;
        }
        int slashes = cut.indexOf(AUTHORITY);
        if (slashes >= 0 && slashes + AUTHORITY.length() <= at) {
            return new UserInfo(slashes + AUTHORITY.length(), at, true);
        }
        int slash = cut.indexOf('/');
        int anchor = slash >= 0 && slash < at ? slash : cut.indexOf('@');
        int colon = cut.lastIndexOf(':', anchor);
        return colon < 0 ? null : new UserInfo(colon + 1, at, false);
    }

    /**
     * User information from {@code start} (inclusive) to the {@code @} at {@code at}, in a
     * {@code //} authority or not.
     */
    private record UserInfo(int start, int at, boolean authority) {
    }

    /** The redacted URL and the driver, never the user nor the password. */
    @Override
    public String toString() {
        return "JdbcParameters[url=" + redactedUrl() + ", driver=" + driver + "]";
    }
}
