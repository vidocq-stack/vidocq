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
package io.vidocq.runtime.extensions.microprofile.knock;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The name a check is shown under, and its keys in one group of the {@code health} panel, derived from the name
 * Knock registers it under so that the same checks always give the same keys.
 *
 * <p>Knock registers a CDI check under the binary name of its class, which is the container's client proxy for a
 * scoped bean, such as {@code com.acme.AppLivenessCheck_ClientProxy}. The check is shown under its simple class
 * name without the container's suffix, {@code AppLivenessCheck}, and its key is that name with its words hyphenated
 * and lowercased, {@code app-liveness-check}, with {@code c-} in front when it does not start with a letter, cut to
 * leave room for its {@value #AT} suffix, and numbered {@code -2}, {@code -3}… on a clash.
 *
 * <p>Not thread safe: one instance per group and per sample.
 */
final class CheckKeys {

    /** The longest key the dev console accepts. */
    static final int MAX_LENGTH = 40;

    /** The suffix of the key that holds when a check answered. */
    static final String AT = ".at";

    /** The suffix Vauban gives the client proxy of a scoped bean. */
    private static final String CLIENT_PROXY = "_ClientProxy";

    private final Set<String> taken;

    /**
     * @param reserved the keys the group writes itself, which no check may take
     */
    CheckKeys(Set<String> reserved) {
        this.taken = new HashSet<>(reserved);
    }

    /**
     * The name a check is shown under: the simple name of its registration name, without the container's suffix.
     *
     * @param registrationName the name Knock registers the check under, usually a binary class name
     * @return the name to show
     */
    static String display(String registrationName) {
        String name = registrationName;
        int generated = name.indexOf("$$");
        if (generated >= 0) {
            name = name.substring(0, generated);
        }
        if (name.endsWith(CLIENT_PROXY)) {
            name = name.substring(0, name.length() - CLIENT_PROXY.length());
        }
        name = name.substring(name.lastIndexOf('.') + 1).replace('$', '.');
        return name.isEmpty() ? registrationName : name;
    }

    /**
     * The key of a display name, neither cut nor made unique: its words hyphenated, lowercased, every character
     * other than a letter, a digit, a dot or a hyphen replaced with a hyphen.
     *
     * @param displayName the name a check is shown under
     * @return its key before it is cut
     */
    static String normalize(String displayName) {
        StringBuilder key = new StringBuilder(displayName.length() + 4);
        for (int i = 0; i < displayName.length(); i++) {
            char c = displayName.charAt(i);
            if (Character.isUpperCase(c) && i > 0 && startsWord(displayName, i)) {
                key.append('-');
            }
            char lower = Character.toLowerCase(c);
            boolean valid = (lower >= 'a' && lower <= 'z') || (lower >= '0' && lower <= '9') || lower == '.'
                    || lower == '-';
            key.append(valid ? lower : '-');
        }
        String result = key.toString().toLowerCase(Locale.ROOT);
        if (result.isEmpty() || result.charAt(0) < 'a' || result.charAt(0) > 'z') {
            result = "c-" + result;
        }
        return result;
    }

    /** An upper case letter after a lower case one or a digit, or the last capital of an acronym before a word. */
    private static boolean startsWord(String name, int i) {
        char previous = name.charAt(i - 1);
        if (Character.isLowerCase(previous) || Character.isDigit(previous)) {
            return true;
        }
        return Character.isUpperCase(previous) && i + 1 < name.length() && Character.isLowerCase(name.charAt(i + 1));
    }

    /**
     * Takes the key of a check in this group, and the key made of it and {@value #AT}.
     *
     * @param displayName the name the check is shown under
     * @return the key
     */
    String allocate(String displayName) {
        String base = normalize(displayName);
        int room = MAX_LENGTH - AT.length();
        for (int n = 1; ; n++) {
            String candidate = n == 1 ? cut(base, room) : cut(base, room - ("-" + n).length()) + "-" + n;
            if (!taken.contains(candidate) && !taken.contains(candidate + AT)) {
                taken.add(candidate);
                taken.add(candidate + AT);
                return candidate;
            }
        }
    }

    private static String cut(String key, int length) {
        return key.length() <= length ? key : key.substring(0, length);
    }
}
