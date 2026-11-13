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
package io.vidocq.cervantes.cdi.internal;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.interceptor.Interceptor;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;

import java.util.ServiceLoader;
import java.util.function.Supplier;

/**
 * Fails the container start on an invalid {@code mp.jwt.*} configuration (unrecognised
 * {@code mp.jwt.verify.publickey.algorithm}, unreadable key, and so on) instead of at the first
 * injection of the {@code @Dependent} validator.
 *
 * <p>The check is an observer of {@link Startup}, not a phase of the build compatible extension
 * (cervantes#52). The configuration to check is the one the application starts with, never the
 * build machine's, and a container may run its extensions when the application builds and not
 * again when it starts (Vauban, Vidocq/vauban#288): an observer is notified at every start, on
 * every container.</p>
 *
 * <p>It runs before the observers of libraries and applications (see {@link #PRIORITY}), so none
 * of them does any work for an application that will not start.</p>
 */
@Dependent
public class JwtConfigurationCheck {

    /**
     * The priority of the observer: the start of the platform range, before every library
     * ({@code LIBRARY_BEFORE}) and application observer. One above
     * {@code Interceptor.Priority.PLATFORM_BEFORE}, whose value is 0: Vauban reads a priority of 0
     * on an observer as "none declared" and gives it the default priority, among the observers of
     * the application.
     */
    static final int PRIORITY = Interceptor.Priority.PLATFORM_BEFORE + 1;

    /**
     * Builds the validator once, to find what is wrong with the configuration, and discards it.
     *
     * <p>Nothing happens when:</p>
     * <ul>
     * <li>no verification key is configured (MP-JWT is off);</li>
     * <li>no MicroProfile Config implementation is present, since nothing can then configure a
     * key. Any other failure while the Config implementation boots is thrown as it is.</li>
     * </ul>
     *
     * @throws DeploymentException if a verification key is configured but the configuration is
     *         invalid; its message is the one of
     *         {@link JwtAuthConfigProducer#createValidator(org.eclipse.microprofile.config.Config)},
     *         which names the property, the bad value and the supported values
     */
    public void checkAtStart(@Observes @Priority(PRIORITY) Startup event) {
        if (!isConfigImplementationAvailable()) {
            return;
        }
        try {
            JwtAuthConfigProducer.createValidator(ConfigProvider.getConfig());
        } catch (RuntimeException e) {
            throw new DeploymentException(e.getMessage(), e);
        }
    }

    /**
     * True unless there is no MicroProfile Config implementation at all, as seen from the
     * application: its resolver and the context class loader.
     */
    static boolean isConfigImplementationAvailable() {
        return isConfigImplementationAvailable(ConfigProviderResolver::instance,
                Thread.currentThread().getContextClassLoader());
    }

    /**
     * True unless there is no MicroProfile Config implementation at all: the API reports that with
     * an {@link IllegalStateException}, which is only taken for "absent" when {@code loader} finds
     * no {@link ConfigProviderResolver} service provider either.
     *
     * @param resolver how the resolver is obtained, {@code ConfigProviderResolver::instance}
     * @param loader the class loader in which a service provider is looked for
     */
    static boolean isConfigImplementationAvailable(Supplier<ConfigProviderResolver> resolver, ClassLoader loader) {
        try {
            resolver.get();
            return true;
        } catch (IllegalStateException e) {
            if (ServiceLoader.load(ConfigProviderResolver.class, loader).findFirst().isEmpty()) {
                return false;
            }
            throw e;
        }
    }
}
