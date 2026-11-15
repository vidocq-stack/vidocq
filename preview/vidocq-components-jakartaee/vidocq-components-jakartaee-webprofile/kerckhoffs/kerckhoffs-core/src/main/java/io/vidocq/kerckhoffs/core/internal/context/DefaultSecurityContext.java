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
package io.vidocq.kerckhoffs.core.internal.context;

import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.SecurityContext;
import jakarta.security.enterprise.authentication.mechanism.http.AuthenticationParameters;
import io.vidocq.kerckhoffs.core.internal.mechanism.DefaultHttpMessageContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.security.Principal;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The {@link SecurityContext} of Jakarta Security 4.0 (section 3.10): it answers for the caller of the request being
 * served, which the container established through the authentication mechanism. It knows no container: the request
 * comes from a supplier.
 *
 * <p>What nothing can decide yet it refuses rather than guesses: web resource access is denied, and the declared
 * roles throw {@link UnsupportedOperationException}.
 */
public final class DefaultSecurityContext implements SecurityContext {

    private final Supplier<Optional<HttpServletRequest>> requests;

    /** @param requests the request being served by the current thread, empty when there is none */
    public DefaultSecurityContext(Supplier<Optional<HttpServletRequest>> requests) {
        this.requests = Objects.requireNonNull(requests, "requests");
    }

    @Override
    public Principal getCallerPrincipal() {
        return requests.get().map(HttpServletRequest::getUserPrincipal).orElse(null);
    }

    @Override
    public <T extends Principal> Set<T> getPrincipalsByType(Class<T> type) {
        Principal caller = getCallerPrincipal();
        return type.isInstance(caller) ? Set.of(type.cast(caller)) : Set.of();
    }

    @Override
    public boolean isCallerInRole(String role) {
        return role != null && requests.get().map(r -> r.isUserInRole(role)).orElse(false);
    }

    @Override
    public Set<String> getAllDeclaredCallerRoles() {
        throw new UnsupportedOperationException("the declared roles are not known yet (Authorization, K3)");
    }

    @Override
    public boolean hasAccessToWebResource(String resource, String... methods) {
        // Fail closed: no authorization decides yet.
        return false;
    }

    @Override
    public AuthenticationStatus authenticate(HttpServletRequest request, HttpServletResponse response,
                                             AuthenticationParameters parameters) {
        // The mechanism reads the parameters from the request, as it reads them for any authentication request.
        request.setAttribute(DefaultHttpMessageContext.PARAMETERS,
                parameters == null ? new AuthenticationParameters() : parameters);
        try {
            if (request.authenticate(response)) return AuthenticationStatus.SUCCESS;
            return response.isCommitted() && response.getStatus() < 400
                    ? AuthenticationStatus.SEND_CONTINUE : AuthenticationStatus.SEND_FAILURE;
        } catch (ServletException | IOException failed) {
            // A mechanism that failed has not authenticated anybody: that is an outcome, not an error.
            return AuthenticationStatus.SEND_FAILURE;
        } finally {
            request.removeAttribute(DefaultHttpMessageContext.PARAMETERS);
        }
    }
}
