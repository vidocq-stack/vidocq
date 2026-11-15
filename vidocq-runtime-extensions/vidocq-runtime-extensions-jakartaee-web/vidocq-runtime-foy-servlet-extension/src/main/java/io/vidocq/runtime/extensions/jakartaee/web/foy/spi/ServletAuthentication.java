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
package io.vidocq.runtime.extensions.jakartaee.web.foy.spi;

import io.vidocq.foy.spi.security.RequestAuthentication;
import jakarta.enterprise.inject.spi.BeanManager;

import java.util.Optional;

/**
 * Takes over the authentication of the Foy deployments: an extension that brings a Jakarta Authentication runtime
 * (the Kerckhoffs extension places the application's {@code HttpAuthenticationMechanism} in service) hands Foy its
 * {@link RequestAuthentication} hook here. Found with {@link java.util.ServiceLoader} among the runtime extensions,
 * the way a {@link ServletDeploymentListener} is.
 *
 * <p>At most one extension may take over: two that answer with a hook fail the deployment with an
 * {@link IllegalStateException}. Without one, the container's own mechanisms ({@code login-config}) apply.
 */
public interface ServletAuthentication {

    /**
     * Called once per deployment, before it is built.
     *
     * @param beanManager the CDI container of the application, or {@code null} when it has none
     * @return the hook to install, or empty to leave the authentication to the container; never {@code null}
     */
    Optional<RequestAuthentication> requestAuthentication(BeanManager beanManager);
}
