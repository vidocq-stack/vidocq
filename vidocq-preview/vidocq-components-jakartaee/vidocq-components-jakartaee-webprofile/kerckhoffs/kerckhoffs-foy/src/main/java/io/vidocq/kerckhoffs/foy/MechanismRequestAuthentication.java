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

import io.vidocq.foy.spi.security.Identity;
import io.vidocq.foy.spi.security.RequestAuthentication;
import io.vidocq.kerckhoffs.authentication.ServletContainerProfile;
import io.vidocq.kerckhoffs.core.internal.mechanism.HttpAuthenticationMechanismModule;
import jakarta.security.auth.message.AuthException;
import jakarta.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanism;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Places an {@link HttpAuthenticationMechanism} in service on the request authentication hook of Foy (Jakarta
 * Security 4.0, section 2.5): each request goes through the Jakarta Authentication Servlet Container Profile, whose
 * server authentication module is the mechanism. The mechanism is asked of {@code mechanism} for each request, so a
 * request-scoped or dependent bean behaves as it does in the specification.
 *
 * <p>It fails closed: whatever the mechanism or the profile breaks reaches Foy as a {@link ServletException}, and Foy
 * does not run the target.
 */
public final class MechanismRequestAuthentication implements RequestAuthentication {

    /** The authentication type of a caller whose mechanism named none. */
    static final String DEFAULT_AUTH_TYPE = "HTTP";

    private final Supplier<HttpAuthenticationMechanism> mechanism;

    public MechanismRequestAuthentication(Supplier<HttpAuthenticationMechanism> mechanism) {
        this.mechanism = Objects.requireNonNull(mechanism, "mechanism");
    }

    @Override
    public Outcome validateRequest(HttpServletRequest request, HttpServletResponse response, boolean mandatory)
            throws ServletException {
        try {
            var result = profile().validateRequest(request, response, mandatory);
            return switch (result.status()) {
                case RESPONSE_SENT -> RequestAuthentication.responseSent();
                case ANONYMOUS -> RequestAuthentication.anonymous();
                case AUTHENTICATED -> RequestAuthentication.authenticated(
                        new Identity(result.caller(), result.groups()),
                        result.authType() == null ? DEFAULT_AUTH_TYPE : result.authType());
            };
        } catch (AuthException | RuntimeException e) {
            throw new ServletException("the authentication mechanism failed", e);
        }
    }

    @Override
    public void cleanSubject(HttpServletRequest request) {
        try {
            profile().cleanSubject(request, null);
        } catch (AuthException | RuntimeException e) {
            throw new IllegalStateException("the authentication mechanism failed to forget the caller", e);
        }
    }

    private ServletContainerProfile profile() throws AuthException {
        HttpAuthenticationMechanism current = mechanism.get();
        if (current == null) throw new AuthException("no HttpAuthenticationMechanism is in service");
        return ServletContainerProfile.of(new HttpAuthenticationMechanismModule(current), Map.of());
    }
}
