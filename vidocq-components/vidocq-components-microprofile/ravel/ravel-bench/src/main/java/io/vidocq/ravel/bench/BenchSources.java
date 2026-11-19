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
package io.vidocq.ravel.bench;

import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.Map;
import java.util.Set;

/**
 * Synthetic sources shared by all benchmarks.
 *
 * <p>All implementations (Ravel, SmallRye) are initialized with
 * <b>the same properties</b> for fair comparison.</p>
 */
final class BenchSources {

    private BenchSources() {}

    /** Scalar property set: 64 keys with simple values (integers, booleans, strings). */
    static final Map<String, String> SCALARS;

    /** Property set with nested expressions (3 levels of ${...}). */
    static final Map<String, String> EXPRESSIONS;

    /** Property set for typed conversions (Integer, Duration, Boolean, List). */
    static final Map<String, String> TYPED;

    static {
        var scalars = new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i < 64; i++) {
            scalars.put("bench.scalar.key" + i, "value-" + i);
        }
        SCALARS = Map.copyOf(scalars);

        EXPRESSIONS = Map.ofEntries(
                Map.entry("bench.env", "prod"),
                Map.entry("bench.zone", "eu-west-1"),
                Map.entry("bench.host.prod", "api.example.com"),
                Map.entry("bench.host.dev", "dev.example.com"),
                Map.entry("bench.host", "${bench.host.${bench.env}}"),
                Map.entry("bench.url", "https://${bench.host}/api/${bench.zone}/v1"),
                Map.entry("bench.deep", "${bench.url}/users/${bench.zone}"),
                Map.entry("bench.literal", "no-expression-here-just-a-plain-string-value")
        );

        TYPED = Map.ofEntries(
                Map.entry("bench.int", "42"),
                Map.entry("bench.long", "9223372036854775806"),
                Map.entry("bench.boolean", "yes"),
                Map.entry("bench.duration", "PT1M30S"),
                Map.entry("bench.list", "alpha,bravo,charlie,delta,echo")
        );
    }

    /** Minimal MicroProfile source backed by an immutable {@code Map}. */
    static final class InMemorySource implements ConfigSource {
        private final String name;
        private final Map<String, String> values;
        private final int ordinal;

        InMemorySource(String name, Map<String, String> values, int ordinal) {
            this.name = name;
            this.values = values;
            this.ordinal = ordinal;
        }

        @Override public Map<String, String> getProperties() { return values; }
        @Override public Set<String> getPropertyNames() { return values.keySet(); }
        @Override public String getValue(String propertyName) { return values.get(propertyName); }
        @Override public String getName() { return name; }
        @Override public int getOrdinal() { return ordinal; }
    }
}

