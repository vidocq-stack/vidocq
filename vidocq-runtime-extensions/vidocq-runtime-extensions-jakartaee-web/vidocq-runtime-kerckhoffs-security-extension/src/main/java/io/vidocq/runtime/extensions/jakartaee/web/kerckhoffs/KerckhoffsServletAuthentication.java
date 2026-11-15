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
package io.vidocq.runtime.extensions.jakartaee.web.kerckhoffs;

import io.vidocq.foy.spi.security.RequestAuthentication;
import io.vidocq.kerckhoffs.foy.MechanismRequestAuthentication;
import io.vidocq.runtime.extensions.jakartaee.web.foy.spi.ServletAuthentication;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanism;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Hands Foy the authentication of an application that has an {@link HttpAuthenticationMechanism} (Jakarta Security
 * 4.0, section 2.5): the bean the application declares, or the one Kerckhoffs registers for its
 * {@code @BasicAuthenticationMechanismDefinition}, {@code @FormAuthenticationMechanismDefinition} or
 * {@code @CustomFormAuthenticationMechanismDefinition}. Without a mechanism the container keeps its own
 * ({@code login-config}).
 *
 * <p>The mechanism is asked of the container for each request, so a request-scoped or dependent bean behaves as the
 * specification says.
 */
public final class KerckhoffsServletAuthentication implements ServletAuthentication {

    @Override
    public Optional<RequestAuthentication> requestAuthentication(BeanManager beanManager) {
        if (beanManager == null || beanManager.getBeans(HttpAuthenticationMechanism.class).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MechanismRequestAuthentication(mechanism(beanManager)));
    }

    /** The mechanism of the application, resolved by the container each time it is asked. */
    static Supplier<HttpAuthenticationMechanism> mechanism(BeanManager beanManager) {
        return () -> {
            Bean<?> bean = beanManager.resolve(beanManager.getBeans(HttpAuthenticationMechanism.class));
            return (HttpAuthenticationMechanism) beanManager.getReference(bean, HttpAuthenticationMechanism.class,
                    beanManager.createCreationalContext(bean));
        };
    }
}
