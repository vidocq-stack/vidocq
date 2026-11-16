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
package io.vidocq.kerckhoffs.foy;

import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanismHandler;
import jakarta.security.enterprise.authentication.mechanism.http.HttpMessageContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A handler is no mechanism (Jakarta Security 4.0, section 3.8.3): it is put in front of a request as one. */
class HandlerMechanismTest {

    /** A handler that records each of its three calls. */
    private static final class Recording implements HttpAuthenticationMechanismHandler {
        final List<String> calls = new ArrayList<>();

        @Override
        public AuthenticationStatus validateRequest(HttpServletRequest request, HttpServletResponse response,
                                                    HttpMessageContext context) {
            calls.add("validateRequest");
            return AuthenticationStatus.SEND_CONTINUE;
        }

        @Override
        public AuthenticationStatus secureResponse(HttpServletRequest request, HttpServletResponse response,
                                                   HttpMessageContext context) {
            calls.add("secureResponse");
            return AuthenticationStatus.SUCCESS;
        }

        @Override
        public void cleanSubject(HttpServletRequest request, HttpServletResponse response, HttpMessageContext context) {
            calls.add("cleanSubject");
        }
    }

    @Test
    void everyCallGoesToTheHandlerAndItsAnswerComesBack() throws Exception {
        var handler = new Recording();
        var mechanism = new HandlerMechanism(handler);

        assertEquals(AuthenticationStatus.SEND_CONTINUE, mechanism.validateRequest(null, null, null));
        assertEquals(AuthenticationStatus.SUCCESS, mechanism.secureResponse(null, null, null));
        mechanism.cleanSubject(null, null, null);

        assertEquals(List.of("validateRequest", "secureResponse", "cleanSubject"), handler.calls);
    }
}
