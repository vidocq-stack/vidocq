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
package io.vidocq.runtime.extensions.microprofile.dirac.live;

import org.eclipse.microprofile.metrics.MetricID;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The keys of one group of the {@code metrics} panel, derived from each metric's name and tags so that the same
 * registry always gives the same keys.
 *
 * <p>A metric name, such as {@code com.acme.Shop.placeOrder} with the tag {@code shop=EU}, is not a valid key of the
 * dev console, {@code [a-z][a-z0-9.-]{0,39}}. Its key is built in four steps:
 * <ol>
 *   <li>the name, then each tag in the order of the tag names, as {@code .name-value}:
 *       {@code com.acme.Shop.placeOrder.shop-EU};</li>
 *   <li>lowercased, and every character other than a letter, a digit, a dot or a hyphen replaced with a hyphen,
 *       with {@code m-} in front when it does not start with a letter: {@code com.acme.shop.placeorder.shop-eu};</li>
 *   <li>cut to 40 characters, less the longest suffix the metric adds, such as {@code .mean} for a timer;</li>
 *   <li>when that key, or one of its suffixed keys, is already taken in the group, the first free {@code -2},
 *       {@code -3}… replaces its end.</li>
 * </ol>
 * Numbers are given in the order the metrics are allocated, which the panel keeps stable: by kind, then by
 * {@link MetricID}. The boot facts list every key next to the metric's name and tags.
 *
 * <p>Not thread safe: one instance per group and per sample.
 */
public final class MetricKeys {

    /** The longest key the dev console accepts. */
    static final int MAX_LENGTH = 40;

    private final Set<String> taken;

    /**
     * @param reserved the keys the group writes itself, which no metric may take
     */
    public MetricKeys(Set<String> reserved) {
        this.taken = new HashSet<>(reserved);
    }

    /**
     * The key of a metric, steps 1 and 2 of the rule: neither cut nor made unique.
     *
     * @param id the metric
     * @return its key before it is cut
     */
    public static String normalize(MetricID id) {
        StringBuilder raw = new StringBuilder(id.getName());
        for (Map.Entry<String, String> tag : id.getTags().entrySet()) {
            raw.append('.').append(tag.getKey()).append('-').append(tag.getValue());
        }
        String lower = raw.toString().toLowerCase(Locale.ROOT);
        StringBuilder key = new StringBuilder(lower.length() + 2);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-';
            key.append(valid ? c : '-');
        }
        if (key.isEmpty() || key.charAt(0) < 'a' || key.charAt(0) > 'z') {
            key.insert(0, "m-");
        }
        return key.toString();
    }

    /**
     * Takes the key of a metric in this group, with the keys made of it and each suffix.
     *
     * @param id       the metric
     * @param suffixes what the metric appends to its key for its other values, such as {@code .mean}
     * @return the key; the metric's other keys are this key followed by each suffix
     */
    public String allocate(MetricID id, String... suffixes) {
        int longest = 0;
        for (String suffix : suffixes) {
            longest = Math.max(longest, suffix.length());
        }
        String base = normalize(id);
        int room = MAX_LENGTH - longest;
        for (int n = 1; ; n++) {
            String candidate = n == 1 ? cut(base, room) : cut(base, room - ("-" + n).length()) + "-" + n;
            if (free(candidate, suffixes)) {
                taken.add(candidate);
                for (String suffix : suffixes) {
                    taken.add(candidate + suffix);
                }
                return candidate;
            }
        }
    }

    private boolean free(String key, String... suffixes) {
        if (taken.contains(key)) {
            return false;
        }
        for (String suffix : suffixes) {
            if (taken.contains(key + suffix)) {
                return false;
            }
        }
        return true;
    }

    private static String cut(String key, int length) {
        return key.length() <= length ? key : key.substring(0, length);
    }
}
