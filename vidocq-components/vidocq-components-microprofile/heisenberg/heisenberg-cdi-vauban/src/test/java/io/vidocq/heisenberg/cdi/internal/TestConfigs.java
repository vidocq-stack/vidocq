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
package io.vidocq.heisenberg.cdi.internal;

import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.eclipse.microprofile.config.spi.ConfigSource;

/** Installs, for the current thread's class loader, a MicroProfile Config made of fixed values. */
final class TestConfigs {

    static final String METRICS_ENABLED = "MP_Fault_Tolerance_Metrics_Enabled";

    private TestConfigs() {
    }

    /** Registers a config holding {@code values}; closing it restores the default config. */
    static AutoCloseable install(Map<String, String> values) {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        resolver.releaseConfig(resolver.getConfig(loader));
        Config config = resolver.getBuilder().withSources(new FixedSource(values)).build();
        resolver.registerConfig(config, loader);
        return () -> resolver.releaseConfig(config);
    }

    private record FixedSource(Map<String, String> values) implements ConfigSource {

        @Override
        public Set<String> getPropertyNames() {
            return values.keySet();
        }

        @Override
        public String getValue(String propertyName) {
            return values.get(propertyName);
        }

        @Override
        public String getName() {
            return "heisenberg-test";
        }

        @Override
        public int getOrdinal() {
            return 1000;
        }
    }
}
