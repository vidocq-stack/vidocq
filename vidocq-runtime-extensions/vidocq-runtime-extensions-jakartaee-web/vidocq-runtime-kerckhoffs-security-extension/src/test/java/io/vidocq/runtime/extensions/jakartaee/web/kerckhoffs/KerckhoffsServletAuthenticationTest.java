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
import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanism;
import jakarta.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanismHandler;
import jakarta.security.enterprise.authentication.mechanism.http.HttpMessageContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
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
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "bean";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A bean manager that knows {@code beans} for the mechanism type and answers {@link #MECHANISM} for them. */
    private static BeanManager beanManager(Set<Bean<?>> beans) {
        return beanManager(Map.of(HttpAuthenticationMechanism.class, beans),
                Map.of(HttpAuthenticationMechanism.class, MECHANISM));
    }

    /** A bean manager that knows the beans of each type, and answers the reference registered for that type. */
    private static BeanManager beanManager(Map<Class<?>, Set<Bean<?>>> beansByType, Map<Class<?>, Object> references) {
        return (BeanManager) Proxy.newProxyInstance(BeanManager.class.getClassLoader(),
                new Class<?>[] {BeanManager.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getBeans" -> beansByType.getOrDefault(args[0], Set.of());
                    case "resolve" -> ((Set<?>) args[0]).iterator().next();
                    case "createCreationalContext" -> null;
                    case "getReference" -> references.get(args[1]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A handler that records that it served a request. */
    private static final class RecordingHandler implements HttpAuthenticationMechanismHandler {
        boolean served;

        @Override
        public AuthenticationStatus validateRequest(HttpServletRequest request, HttpServletResponse response,
                                                    HttpMessageContext context) {
            served = true;
            return AuthenticationStatus.SUCCESS;
        }
    }

    @Test
    void aHandlerAloneIsEnoughToTakeOverTheAuthentication() {
        var manager = beanManager(Map.of(HttpAuthenticationMechanismHandler.class, Set.of(bean())),
                Map.of(HttpAuthenticationMechanismHandler.class, new RecordingHandler()));

        assertTrue(new KerckhoffsServletAuthentication().requestAuthentication(manager).isPresent());
    }

    @Test
    void theHandlerOfTheApplicationServesTheRequestsWhenThereAreSeveralMechanisms() throws Exception {
        var handler = new RecordingHandler();
        var manager = beanManager(
                Map.of(HttpAuthenticationMechanism.class, new java.util.LinkedHashSet<>(java.util.List.of(bean(), bean())),
                        HttpAuthenticationMechanismHandler.class, Set.of(bean())),
                Map.of(HttpAuthenticationMechanism.class, MECHANISM, HttpAuthenticationMechanismHandler.class, handler));

        var status = KerckhoffsServletAuthentication.mechanism(manager).get().validateRequest(null, null, null);

        assertTrue(handler.served);
        assertEquals(AuthenticationStatus.SUCCESS, status);
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
