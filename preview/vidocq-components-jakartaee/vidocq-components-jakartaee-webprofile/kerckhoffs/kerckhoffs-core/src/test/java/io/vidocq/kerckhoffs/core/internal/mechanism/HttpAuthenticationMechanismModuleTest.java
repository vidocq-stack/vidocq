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

import jakarta.security.auth.message.AuthException;
import jakarta.security.auth.message.AuthStatus;
import jakarta.security.enterprise.AuthenticationException;
import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanism;
import jakarta.security.enterprise.authentication.mechanism.http.HttpMessageContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import javax.security.auth.Subject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The server authentication module that places an {@code HttpAuthenticationMechanism} in service (Jakarta Security 4.0,
 * section 2.5). What the mechanism cannot be trusted with is decided here: a mechanism that fails or answers nothing
 * is a failed request, never an anonymous success.
 */
class HttpAuthenticationMechanismModuleTest {

    private final Map<String, Object> policy = new HashMap<>();
    private final Fakes.Info info = new Fakes.Info(Fakes.request(new HashMap<>(), new ArrayList<>()),
            Fakes.response(new ArrayList<>()), policy);
    private final Subject client = new Subject();
    private final List<String> cleaned = new ArrayList<>();

    private HttpAuthenticationMechanismModule module(HttpAuthenticationMechanism mechanism) throws AuthException {
        var module = new HttpAuthenticationMechanismModule(mechanism);
        module.initialize(null, null, callbacks -> { }, Map.of());
        return module;
    }

    private static HttpAuthenticationMechanism answering(AuthenticationStatus status) {
        return new HttpAuthenticationMechanism() {
            @Override public AuthenticationStatus validateRequest(HttpServletRequest request,
                    HttpServletResponse response, HttpMessageContext context) {
                return status;
            }

            @Override public AuthenticationStatus secureResponse(HttpServletRequest request,
                    HttpServletResponse response, HttpMessageContext context) {
                return status;
            }
        };
    }

    @Test
    void aMechanismThatFailsIsAFailedRequest() throws AuthException {
        HttpAuthenticationMechanism broken = (request, response, context) -> {
            throw new AuthenticationException("boom");
        };
        var module = module(broken);

        assertThrows(AuthException.class, () -> module.validateRequest(info, client, null));
    }

    @Test
    void aMechanismThatAnswersNothingIsAFailedRequest() throws AuthException {
        var module = module(answering(null));

        assertThrows(AuthException.class, () -> module.validateRequest(info, client, null));
    }

    @Test
    void aModuleNobodyInitializedRefusesTheRequest() {
        var module = new HttpAuthenticationMechanismModule(answering(AuthenticationStatus.SUCCESS));

        assertThrows(IllegalStateException.class, () -> module.validateRequest(info, client, null));
    }

    @Test
    void theStatusesOfTheMechanismBecomeThoseOfTheProfile() throws Exception {
        assertEquals(AuthStatus.SEND_FAILURE,
                module(answering(AuthenticationStatus.SEND_FAILURE)).validateRequest(info, client, null));
        assertEquals(AuthStatus.SEND_CONTINUE,
                module(answering(AuthenticationStatus.SEND_CONTINUE)).validateRequest(info, client, null));
        assertEquals(AuthStatus.SUCCESS,
                module(answering(AuthenticationStatus.SUCCESS)).validateRequest(info, client, null));
        assertEquals(AuthStatus.SUCCESS, module(answering(AuthenticationStatus.NOT_DONE)).validateRequest(info, client, null),
                "nobody was identified: the container refuses a protected resource itself");
    }

    @Test
    void theResponseIsSecuredWithTheSameStatuses() throws Exception {
        assertEquals(AuthStatus.SEND_SUCCESS,
                module(answering(AuthenticationStatus.SUCCESS)).secureResponse(info, null));
        assertEquals(AuthStatus.SEND_FAILURE,
                module(answering(AuthenticationStatus.SEND_FAILURE)).secureResponse(info, null));
        assertEquals(AuthStatus.SEND_SUCCESS,
                module(answering(AuthenticationStatus.NOT_DONE)).secureResponse(info, null));
    }

    @Test
    void theSubjectIsCleanedByTheMechanism() throws Exception {
        var mechanism = new HttpAuthenticationMechanism() {
            @Override public AuthenticationStatus validateRequest(HttpServletRequest request,
                    HttpServletResponse response, HttpMessageContext context) {
                return AuthenticationStatus.SUCCESS;
            }

            @Override public void cleanSubject(HttpServletRequest request, HttpServletResponse response,
                    HttpMessageContext context) {
                cleaned.add("cleaned");
            }
        };

        module(mechanism).cleanSubject(info, client);

        assertEquals(List.of("cleaned"), cleaned);
    }

    @Test
    void theModuleSpeaksServletMessages() throws Exception {
        assertEquals(List.of(HttpServletRequest.class, HttpServletResponse.class),
                List.of(module(answering(AuthenticationStatus.SUCCESS)).getSupportedMessageTypes()));
    }
}
