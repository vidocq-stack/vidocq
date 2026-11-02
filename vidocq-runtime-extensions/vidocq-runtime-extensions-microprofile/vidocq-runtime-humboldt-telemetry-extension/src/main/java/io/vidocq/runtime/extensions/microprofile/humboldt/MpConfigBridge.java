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
package io.vidocq.runtime.extensions.microprofile.humboldt;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads the {@code otel.*} properties of MicroProfile Config through its API. The extension only
 * {@code requires static} the Config module: when the runtime does not assemble it, the first call
 * throws a {@link LinkageError} and {@link HumboldtExtension} reads Config by reflection instead.
 */
final class MpConfigBridge {

    private MpConfigBridge() {
    }

    static void bridge(Map<String, String> env) {
        Config config = ConfigProvider.getConfig();
        for (String name : config.getPropertyNames()) {
            if (name.startsWith("otel.")) {
                // a foreign Config may answer null: read it as empty
                Objects.requireNonNullElse(config.getOptionalValue(name, String.class), Optional.<String>empty())
                        .ifPresent(v -> env.put(name.toUpperCase(Locale.ROOT).replace('.', '_'), v));
            }
        }
    }
}
