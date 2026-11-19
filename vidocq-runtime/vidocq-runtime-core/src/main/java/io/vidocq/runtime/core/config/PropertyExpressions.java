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
package io.vidocq.runtime.core.config;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.function.UnaryOperator;

/**
 * Expands the property expressions of a configuration value, as MicroProfile Config 3.1 defines them and Ravel
 * expands them, so that {@link VidocqConfigImpl} and {@code ConfigProvider.getConfig()} give one key the same value
 * (BUG-20261114-01).
 *
 * <ul>
 *   <li>{@code ${key}} is the value of {@code key}, itself expanded; {@code ${key:default}} is {@code default} when
 *       {@code key} has no value. The default and the key may hold expressions: {@code ${url.${target}}}.</li>
 *   <li>{@code \$} is a literal {@code $}: {@code \${key}} is kept as {@code ${key}}.</li>
 *   <li>An expression without a default whose key has no value leaves the whole property without a value.</li>
 *   <li>An expression that comes back to the key it expands, or a {@code ${} without its closing brace, is an
 *       {@link IllegalArgumentException}.</li>
 *   <li>{@value #ENABLED_KEY}{@code =false} turns the expansion off: values are read as written.</li>
 * </ul>
 */
final class PropertyExpressions {

    /** The MicroProfile Config key that turns the expansion off; Ravel reads it too. */
    static final String ENABLED_KEY = "mp.config.property.expressions.enabled";

    private final UnaryOperator<String> rawLookup;

    /**
     * @param rawLookup the value of a key as its source holds it, {@code null} when no source has the key; it is
     *                  asked for {@value #ENABLED_KEY} only when a value holds a {@code $}, so building the
     *                  configuration never reads a source
     */
    PropertyExpressions(UnaryOperator<String> rawLookup) {
        this.rawLookup = rawLookup;
    }

    /**
     * The value of {@code key}, whose source holds {@code raw}, with its expressions expanded; empty when an
     * expression without a default names a key that has no value.
     *
     * @throws IllegalArgumentException when an expression is circular or has no closing brace
     */
    Optional<String> expand(String key, String raw) {
        return Optional.ofNullable(value(key, raw, new LinkedHashSet<>()));
    }

    /** The expanded value of {@code key}, {@code null} when an expression cannot be resolved. */
    private String value(String key, String raw, SequencedSet<String> expanding) {
        if (raw.indexOf('$') < 0 || !enabled()) {
            return raw;
        }
        if (!expanding.add(key)) {
            throw new IllegalArgumentException(
                    "Circular property expression: " + String.join(" -> ", expanding) + " -> " + key);
        }
        try {
            return template(raw, expanding);
        } finally {
            expanding.remove(key);
        }
    }

    /** Whether {@value #ENABLED_KEY} leaves the expansion on: anything but {@code false}, in any case. */
    private boolean enabled() {
        String flag = rawLookup.apply(ENABLED_KEY);
        return flag == null || !flag.trim().equalsIgnoreCase("false");
    }

    /** {@code text} with its expressions expanded, {@code null} when one cannot be resolved. */
    private String template(String text, SequencedSet<String> expanding) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            if (text.startsWith("\\$", i)) {
                out.append('$');
                i += 2;
            } else if (text.startsWith("${", i)) {
                int end = closingBrace(text, i + 2);
                if (end < 0) {
                    throw new IllegalArgumentException("Unterminated property expression in: " + text);
                }
                String expanded = expression(text.substring(i + 2, end), expanding);
                if (expanded == null) {
                    return null;
                }
                out.append(expanded);
                i = end + 1;
            } else {
                out.append(text.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    /** The value of the body of one {@code ${...}}, {@code null} when it cannot be resolved. */
    private String expression(String body, SequencedSet<String> expanding) {
        int separator = defaultSeparator(body);
        String key = template(separator < 0 ? body : body.substring(0, separator), expanding);
        if (key == null) {
            return null;
        }
        if (!key.isEmpty()) {
            String raw = rawLookup.apply(key);
            if (raw != null) {
                return value(key, raw, expanding);
            }
        }
        return separator < 0 ? null : template(body.substring(separator + 1), expanding);
    }

    /** The index of the brace that closes the expression whose body starts at {@code from}, or -1. */
    private static int closingBrace(String text, int from) {
        int depth = 1;
        int i = from;
        while (i < text.length()) {
            if (text.startsWith("${", i)) {
                depth++;
                i += 2;
            } else {
                if (text.charAt(i) == '}') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
                i++;
            }
        }
        return -1;
    }

    /** The index of the {@code :} that starts the default of {@code body}, outside any nested expression, or -1. */
    private static int defaultSeparator(String body) {
        int depth = 0;
        int i = 0;
        while (i < body.length()) {
            if (body.startsWith("${", i)) {
                depth++;
                i += 2;
            } else {
                char c = body.charAt(i);
                if (c == ':' && depth == 0) {
                    return i;
                }
                if (c == '}' && depth > 0) {
                    depth--;
                }
                i++;
            }
        }
        return -1;
    }
}
