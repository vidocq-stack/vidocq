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
package io.vidocq.runtime.devservices.host;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Whether a configuration key names a secret, and how to strip credentials out of a value — the rule the
 * {@linkplain StateFile state file} (spec §4.2) uses so that "secrets never enter the file."
 */
public final class SecretMasking {

    private static final List<String> SECRET_ENDINGS = List.of(
            "password", "passwd", "pwd", "secret", "token", "key",
            "credential", "credentials", "apikey", "api-key", "private-key");
    private static final Pattern USER_INFO = Pattern.compile("(//)[^/@\\s]+@");
    private static final Pattern SECRET_PARAM = Pattern.compile(
            "([?&;](?:password|passwd|pwd|secret|token|apikey|api-key)=)[^&;]*", Pattern.CASE_INSENSITIVE);

    private SecretMasking() {}

    /** Whether the last dot-separated segment of {@code key}, lower-cased, names a secret (spec §4.2). */
    public static boolean isSecret(String key) {
        String last = key.substring(key.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return SECRET_ENDINGS.stream().anyMatch(last::endsWith);
    }

    /**
     * {@code value} without the {@code user:password@} part of a URL and with secret query parameters replaced
     * by {@code ***}. Returns {@code null} for a {@code null} value, and any other value unchanged.
     */
    public static String withoutCredentials(String value) {
        if (value == null) {
            return null;
        }
        String out = USER_INFO.matcher(value).replaceAll("$1***@");
        return SECRET_PARAM.matcher(out).replaceAll("$1***");
    }
}
