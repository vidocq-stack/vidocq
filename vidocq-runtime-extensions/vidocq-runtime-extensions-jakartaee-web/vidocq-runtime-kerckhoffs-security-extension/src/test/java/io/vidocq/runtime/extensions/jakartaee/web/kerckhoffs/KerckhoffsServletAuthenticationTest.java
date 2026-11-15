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

import io.vidocq.kerckhoffs.foy.MechanismRequestAuthentication;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanism;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Kerckhoffs extension takes over the authentication only when the application has a mechanism. */
class KerckhoffsServletAuthenticationTest {

    private static final HttpAuthenticationMechanism MECHANISM = (request, response, context) -> null;

    private static Bean<?> bean() {
        return (Bean<?>) Proxy.newProxyInstance(Bean.class.getClassLoader(), new Class<?>[] {Bean.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A bean manager that knows {@code beans} for the mechanism type and answers {@link #MECHANISM} for them. */
    private static BeanManager beanManager(Set<Bean<?>> beans) {
        return (BeanManager) Proxy.newProxyInstance(BeanManager.class.getClassLoader(),
                new Class<?>[] {BeanManager.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getBeans" -> beans;
                    case "resolve" -> beans.iterator().next();
                    case "createCreationalContext" -> null;
                    case "getReference" -> MECHANISM;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void withoutABeanManagerTheContainerKeepsTheAuthentication() {
        assertTrue(new KerckhoffsServletAuthentication().requestAuthentication(null).isEmpty());
    }

    @Test
    void withoutAMechanismTheContainerKeepsTheAuthentication() {
        assertTrue(new KerckhoffsServletAuthentication().requestAuthentication(beanManager(Set.of())).isEmpty());
    }

    @Test
    void aMechanismBeanPutsKerckhoffsInChargeOfEveryRequest() {
        var hook = new KerckhoffsServletAuthentication().requestAuthentication(beanManager(Set.of(bean())));

        assertInstanceOf(MechanismRequestAuthentication.class, hook.orElseThrow());
    }

    @Test
    void theMechanismIsAskedOfTheContainerEachTime() {
        var manager = beanManager(Set.of(bean()));

        assertSame(MECHANISM, KerckhoffsServletAuthentication.mechanism(manager).get());
        assertEquals(MECHANISM, KerckhoffsServletAuthentication.mechanism(manager).get());
    }
}
