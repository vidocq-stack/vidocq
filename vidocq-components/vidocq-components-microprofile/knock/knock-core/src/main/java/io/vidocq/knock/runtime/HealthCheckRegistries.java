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
package io.vidocq.knock.runtime;

import io.vidocq.knock.internal.KnockHealthCheckRegistry;
import io.vidocq.knock.spi.HealthCheckRegistry;

/**
 * Exported factory for Knock {@link HealthCheckRegistry}s.
 *
 * <p>Stable entry point for Knock integration modules
 * ({@code knock-cdi-vauban}, {@code knock-cassini}) that need a registry instance
 * without depending on the internal {@code io.vidocq.knock.internal} package
 * (which remains unexported — project Java Modules boundary).</p>
 *
 * <p>Implementation: delegates to {@link KnockHealthCheckRegistry} (thread-safe,
 * virtual-thread-friendly).</p>
 */
public final class HealthCheckRegistries {

    private HealthCheckRegistries() {
        // utility class
    }

    /**
     * Creates a new empty Knock {@link HealthCheckRegistry}.
     *
     * @return a new thread-safe, ready-to-use instance
     */
    public static HealthCheckRegistry newRegistry() {
        return new KnockHealthCheckRegistry();
    }
}
