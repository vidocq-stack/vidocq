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
package io.vidocq.runtime.spi.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VidocqConfigTest {

    @Test
    void theDefaultActiveProfileIsTheVidocqOneElseTheMicroProfileOne() {
        assertEquals(Optional.of("dev"), config(Map.of("vidocq.profile", " dev ", "mp.config.profile", "prod"))
                .activeProfile());
        assertEquals(Optional.of("prod"), config(Map.of("mp.config.profile", "prod")).activeProfile());
        assertEquals(Optional.empty(), config(Map.of("vidocq.profile", " ")).activeProfile());
        assertEquals(Optional.empty(), config(Map.of()).activeProfile());
    }

    /** A configuration of fixed values, which keeps the default {@link VidocqConfig#activeProfile()}. */
    private static VidocqConfig config(Map<String, String> values) {
        return new VidocqConfig() {
            @Override
            public Optional<String> getValue(String key) {
                return Optional.ofNullable(values.get(key));
            }

            @Override
            public <T> Optional<T> getValue(String key, Class<T> type) {
                return getValue(key).map(type::cast);
            }

            @Override
            public <T> List<T> getValues(String key, Class<T> elementType) {
                return List.of();
            }

            @Override
            public Iterable<String> getPropertyNames() {
                return values.keySet();
            }

            @Override
            public Iterable<ConfigSource> getConfigSources() {
                return List.of();
            }
        };
    }
}
