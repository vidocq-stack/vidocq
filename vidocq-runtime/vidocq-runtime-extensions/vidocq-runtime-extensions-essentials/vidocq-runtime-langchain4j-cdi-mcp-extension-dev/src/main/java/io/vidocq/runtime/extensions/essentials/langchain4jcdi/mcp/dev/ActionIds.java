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
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/**
 * The ids of the inspector's actions, one instance per catalogue. An id follows the console's key rule, a lowercase
 * letter then at most 39 lowercase letters, digits, dots or hyphens: a prefix, then the name lowercased with every
 * other character turned into {@code -}, or the first 8 hex characters of the SHA-256 of a URI. It is cut to fit,
 * and a collision gets {@code -2}, {@code -3} and so on. Built in name order, ids are the same on every reload as
 * long as the names do not change.
 */
final class ActionIds {

    /** The longest id the console accepts. */
    static final int MAX = 40;

    private final Set<String> taken = new HashSet<>();

    /** The id of a tool or a prompt: {@code prefix}, then the slug of {@code name}. */
    String named(String prefix, String name) {
        return unique(prefix + slug(name));
    }

    /** The id of a resource or a template: {@code prefix}, then the first 8 hex characters of the SHA-256 of it. */
    String hashed(String prefix, String uri) {
        return unique(prefix + sha8(uri));
    }

    /** {@code name} lowercased, every character but {@code a-z}, {@code 0-9}, {@code .} and {@code -} a hyphen. */
    static String slug(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            boolean kept = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-';
            out.append(kept ? c : '-');
        }
        return out.toString();
    }

    /** The first 8 hex characters of the SHA-256 of {@code text}, in UTF-8. */
    static String sha8(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("every JDK has SHA-256", impossible);
        }
    }

    private String unique(String wanted) {
        String base = cut(wanted, MAX);
        if (taken.add(base)) {
            return base;
        }
        for (int n = 2; ; n++) {
            String suffix = "-" + n;
            String candidate = cut(wanted, MAX - suffix.length()) + suffix;
            if (taken.add(candidate)) {
                return candidate;
            }
        }
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
