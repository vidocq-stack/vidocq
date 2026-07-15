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
package io.vidocq.knock.cdi.internal;

import io.vidocq.knock.runtime.HealthCheckRegistries;
import io.vidocq.knock.spi.HealthCheckRegistry;
import io.vidocq.knock.spi.ProbeType;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;

import java.util.List;

/**
 * CDI {@link HealthCheckRegistry} bean — application singleton injectable via
 * {@code @Inject HealthCheckRegistry}.
 *
 * <p>Delegates to a registry obtained from the SPI factory
 * {@link HealthCheckRegistries#newRegistry()} (the internal package
 * {@code io.vidocq.knock.internal} is not exported — project Java Modules boundary).
 * Exposed as an {@code @ApplicationScoped} bean implementing the
 * {@link HealthCheckRegistry} interface so that the CDI proxy directly implements
 * the interface and can be cast without ambiguity.</p>
 */
@ApplicationScoped
class KnockCdiHealthCheckRegistry implements HealthCheckRegistry {

    private final HealthCheckRegistry delegate = HealthCheckRegistries.newRegistry();

    @Override
    public void register(ProbeType type, String name, HealthCheck check) {
        delegate.register(type, name, check);
    }

    @Override
    public void unregister(String name) {
        delegate.unregister(name);
    }

    @Override
    public List<HealthCheck> getChecks(ProbeType type) {
        return delegate.getChecks(type);
    }
}
