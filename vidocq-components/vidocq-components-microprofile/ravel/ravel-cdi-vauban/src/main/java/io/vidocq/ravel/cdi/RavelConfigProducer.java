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
package io.vidocq.ravel.cdi;

import io.vidocq.ravel.cdi.internal.RavelConfigPropertyResolver;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.InjectionPoint;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Legacy CDI producers: keeps {@code @Produces Config} and provides
 * {@link #produceConfigProperty(InjectionPoint)} for unit tests resolving
 * {@code @ConfigProperty} without starting a CDI container.
 *
 * <p>In production CDI, {@code @ConfigProperty} resolution is handled by
 * synthetic beans registered by {@link ConfigCdiExtension} and delegated to
 * {@link RavelConfigPropertyResolver}.</p>
 */
@Dependent
public class RavelConfigProducer {

    @Produces
    @Dependent
    public Config produceConfig() {
        return ConfigProvider.getConfig();
    }

    /** Non-CDI entry point used by unit tests, delegating to resolver logic. */
    public Object produceConfigProperty(InjectionPoint injectionPoint) {
        return RavelConfigPropertyResolver.resolve(injectionPoint);
    }
}
