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
package io.vidocq.runtime.cli.completion;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The short aliases of the options, per context ({@code -x} is {@code --extension} for {@code create}): the one
 * table that {@link io.vidocq.runtime.cli.CliParser}'s help prints and {@link CompletionSpec} offers after a single
 * dash (Vidocq/vidocq#782). {@code CliParserShortOptionsTest} checks that the parser accepts exactly these.
 */
public final class ShortOptions {

    private static final String PORT = "--port";
    private static final String OFFLINE = "--offline";

    /** Context, then short alias to long form, in the order completion offers them. */
    private static final Map<String, Map<String, String>> BY_CONTEXT = byContext(
            "start", List.of("-p", PORT, "-c", "--config"),
            "dev", List.of("-p", PORT, "-P", "--profile"),
            "doctor", List.of("-v", "--verbose"),
            "create", List.of("-n", "--name", "-g", "--group-id", "-x", "--extension"),
            "build", List.of("-o", OFFLINE),
            "clean", List.of("-o", OFFLINE));

    private ShortOptions() {}

    /** The contexts that have short aliases. */
    public static List<String> contexts() {
        return List.copyOf(BY_CONTEXT.keySet());
    }

    /** The short aliases of {@code context}, each with its long form; empty when it has none. */
    public static Map<String, String> of(String context) {
        return BY_CONTEXT.getOrDefault(context, Map.of());
    }

    /** {@code longOption} as the help names it: {@code --port, -p} when it has a short alias, else itself. */
    public static String label(String context, String longOption) {
        return of(context).entrySet().stream()
                .filter(e -> e.getValue().equals(longOption))
                .map(e -> longOption + ", " + e.getKey())
                .findFirst()
                .orElse(longOption);
    }

    private static Map<String, Map<String, String>> byContext(Object... contextsAndPairs) {
        Map<String, Map<String, String>> map = new LinkedHashMap<>();
        for (int i = 0; i < contextsAndPairs.length; i += 2) {
            @SuppressWarnings("unchecked")
            List<String> pairs = (List<String>) contextsAndPairs[i + 1];
            Map<String, String> aliases = new LinkedHashMap<>();
            for (int j = 0; j < pairs.size(); j += 2) {
                aliases.put(pairs.get(j), pairs.get(j + 1));
            }
            map.put((String) contextsAndPairs[i], Collections.unmodifiableMap(aliases));
        }
        return Collections.unmodifiableMap(map);
    }
}
