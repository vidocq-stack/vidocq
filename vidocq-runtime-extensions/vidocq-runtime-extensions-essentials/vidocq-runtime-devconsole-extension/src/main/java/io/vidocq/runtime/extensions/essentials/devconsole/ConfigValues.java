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
package io.vidocq.runtime.extensions.essentials.devconsole;

import io.vidocq.runtime.spi.report.LaunchMode;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the {@code config} panel may show of a configured value, decided in the JVM before anything reaches the page:
 *
 * <ul>
 *   <li>outside a {@link LaunchMode#DEV dev} launch, nothing: {@value #NOT_SHOWN};</li>
 *   <li>for a key whose last segment names a secret, {@value #MASKED}, as {@code StartupReportSection.secret} writes
 *       it: {@code password}, {@code passwd}, {@code pwd}, {@code secret}, {@code token}, {@code key},
 *       {@code credential(s)}, {@code apikey}, {@code api-key} or {@code private-key}, in any case, alone or as the
 *       end of the segment, so {@code db.adminPassword} and {@code DB_PASSWORD} are secrets too;</li>
 *   <li>otherwise the value, without the credentials of a URL: the {@code user:password@} before its host becomes
 *       {@value #HIDDEN}{@code @}, and the value of a query parameter that names a secret by the same rule, such as
 *       {@code password=} or {@code pwd=}, becomes {@value #HIDDEN}.</li>
 * </ul>
 *
 * <p>The rule is on the key, never on the value: a value that merely contains the word {@code password} is shown
 * when its key names no secret. A false positive costs a masked value, never a leak.
 */
final class ConfigValues {

    /** What a secret key's value reads: that it is set, never what it is. */
    static final String MASKED = "configured";
    /** What every value reads outside a dev launch. */
    static final String NOT_SHOWN = "not shown outside dev";
    /** What replaces the credentials of a URL and the value of a secret query parameter. */
    static final String HIDDEN = "***";

    /** The words that make a key segment, or a query parameter, name a secret when it ends with one. */
    private static final List<String> SECRET_WORDS = List.of("password", "passwd", "pwd", "secret", "token", "key",
            "credentials", "credential", "apikey", "api-key", "private-key");

    /**
     * The user information of a URL, {@code scheme://user:password@}: greedy up to the last {@code @} before the
     * path, so a password holding an unencoded {@code @} is removed whole.
     */
    private static final Pattern URL_CREDENTIALS = Pattern.compile("([A-Za-z][A-Za-z0-9+.:-]*://)[^/\\s]*@");

    /** A {@code name=value} parameter of a query or of a JDBC URL's {@code ;} list. */
    private static final Pattern PARAMETER = Pattern.compile("([?&;])([^=&;#?\\s]+)=([^&;#\\s]*)");

    private ConfigValues() {
    }

    /**
     * What the panel shows of {@code value}, the value of {@code key}, in a launch of {@code mode}.
     *
     * @return the value to show, or {@code null} when {@code value} is {@code null} in a dev launch
     */
    static String shown(String key, String value, LaunchMode mode) {
        if (mode != LaunchMode.DEV) {
            return NOT_SHOWN;
        }
        if (isSecretKey(key)) {
            return MASKED;
        }
        return withoutCredentials(value);
    }

    /**
     * Whether the last segment of {@code key}, after its last dot, names a secret.
     *
     * @param key the configuration key, or {@code null}
     */
    static boolean isSecretKey(String key) {
        if (key == null) {
            return false;
        }
        return namesSecret(key.substring(key.lastIndexOf('.') + 1));
    }

    /**
     * {@code value} without the user and password of any URL in it, and with the value of every query parameter
     * that names a secret replaced by {@value #HIDDEN}.
     *
     * @param value the value, or {@code null}
     * @return the value to show, or {@code null}
     */
    static String withoutCredentials(String value) {
        if (value == null) {
            return null;
        }
        String withoutUsers = URL_CREDENTIALS.matcher(value).replaceAll(
                match -> Matcher.quoteReplacement(match.group(1) + HIDDEN + "@"));
        return PARAMETER.matcher(withoutUsers).replaceAll(match -> Matcher.quoteReplacement(
                namesSecret(match.group(2)) ? match.group(1) + match.group(2) + "=" + HIDDEN : match.group()));
    }

    private static boolean namesSecret(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String word : SECRET_WORDS) {
            if (lower.endsWith(word)) {
                return true;
            }
        }
        return false;
    }
}
