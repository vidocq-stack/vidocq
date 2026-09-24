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

import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Resolves {@code vidocq.dev.devServices}, the switch every host ({@code vidocq:dev}, {@code vidocq:run}, the JUnit
 * launcher) reads the same way (spec §5, first match wins):
 * <ol>
 *     <li>the explicit value, when there is one: a {@code -D}, the goal's configuration, a system property;</li>
 *     <li>then the key in the application's files ({@link ApplicationFiles});</li>
 *     <li>then the host's default: on for {@code vidocq:dev} and tests, off for {@code vidocq:run}.</li>
 * </ol>
 * A value is {@code true} or {@code false}, trimmed and in any case.
 */
public final class DevServicesFlag {

    /** The key, in every source. */
    public static final String KEY = "vidocq.dev.devServices";

    private DevServicesFlag() {}

    /**
     * Whether the host starts its dev services.
     *
     * @param explicit     the value given to the host itself, if any
     * @param files        the application's {@code vidocq.dev.*} keys, such as {@link ApplicationFiles#of}
     * @param defaultValue the host's default, used when neither source has the key
     * @throws IllegalArgumentException when the value that wins is neither {@code true} nor {@code false}
     */
    public static boolean enabled(Optional<String> explicit, Function<String, Optional<String>> files,
            boolean defaultValue) {
        Optional<String> value = explicit.filter(v -> !v.isBlank())
                .or(() -> files.apply(KEY).filter(v -> !v.isBlank()));
        return value.map(DevServicesFlag::parse).orElse(defaultValue);
    }

    private static boolean parse(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(
                    KEY + " must be true or false, not '" + value + "'");
        };
    }
}
