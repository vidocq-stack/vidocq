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
package io.vidocq.kerckhoffs.core.internal.mechanism;

import jakarta.security.enterprise.AuthenticationStatus;
import org.junit.jupiter.api.Test;

import javax.security.auth.Subject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @AutoApplySession} (Jakarta Security 4.0, section 3.8.6): the caller a mechanism established is kept in the
 * session and applied again to the next requests, without the mechanism being asked. Nothing is kept for a caller
 * nobody identified.
 */
class AutoApplySessionSupportTest {

    private final Fakes.Exchange exchange = new Fakes.Exchange();
    private final Map<String, Object> policy = new HashMap<>();
    private final List<String> logins = new ArrayList<>();
    private int asked;

    private DefaultHttpMessageContext context() {
        var info = new Fakes.Info(Fakes.request(new HashMap<>(), new ArrayList<>(), Map.of(), exchange),
                Fakes.response(new ArrayList<>()), policy);
        return new DefaultHttpMessageContext(info, new Subject(), callbacks -> logins.add("login"));
    }

    private AuthenticationStatus request(DefaultHttpMessageContext context, boolean mechanismLogsIn)
            throws Exception {
        return AutoApplySessionSupport.validateRequest(context.getRequest(), context, () -> {
            asked++;
            return mechanismLogsIn ? context.notifyContainerAboutLogin("reza", Set.of("foo", "bar"))
                    : context.doNothing();
        });
    }

    @Test
    void nobodyIsRememberedWhenNobodyWasIdentified() throws Exception {
        exchange.hasSession = false;

        assertEquals(AuthenticationStatus.NOT_DONE, request(context(), false));

        assertFalse(exchange.hasSession, "no session is opened for nobody");
        assertTrue(exchange.session.isEmpty());
    }

    @Test
    void aCallerTheMechanismIdentifiedIsKeptInTheSession() throws Exception {
        assertEquals(AuthenticationStatus.SUCCESS, request(context(), true));

        assertFalse(exchange.session.isEmpty());
        assertEquals(1, asked);
    }

    @Test
    void theSessionIdChangesWhenTheCallerLogsIn() throws Exception {
        request(context(), true);

        assertEquals(List.of("changeSessionId"), exchange.sessionEvents, "a login never keeps the session id");
    }

    @Test
    void theNextRequestIsAppliedWithoutAskingTheMechanism() throws Exception {
        request(context(), true);
        logins.clear();

        var next = context();
        assertEquals(AuthenticationStatus.SUCCESS, request(next, false));

        assertEquals(1, asked, "the mechanism was not asked again");
        assertEquals("reza", next.getCallerPrincipal().getName());
        assertEquals(Set.of("foo", "bar"), next.getGroups());
        assertEquals(List.of("login"), logins, "the container is told about the caller again");
    }

    @Test
    void aLogoutForgetsTheCaller() throws Exception {
        request(context(), true);

        AutoApplySessionSupport.cleanSubject(context().getRequest());

        assertEquals(AuthenticationStatus.NOT_DONE, request(context(), false));
        assertEquals(2, asked);
    }

    @Test
    void aSessionOfAnotherKindIsNotACaller() throws Exception {
        exchange.session.put(AutoApplySessionSupport.IDENTITY, "reza");

        assertEquals(AuthenticationStatus.NOT_DONE, request(context(), false));

        assertEquals(1, asked, "only what this support stored is trusted");
    }
}
