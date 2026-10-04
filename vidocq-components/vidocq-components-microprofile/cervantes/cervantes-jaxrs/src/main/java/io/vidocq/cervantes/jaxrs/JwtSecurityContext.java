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
package io.vidocq.cervantes.jaxrs;

import jakarta.ws.rs.core.SecurityContext;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.security.Principal;
import java.util.Set;

/**
 * JAX-RS {@link SecurityContext} backed by a validated {@link JsonWebToken} (MicroProfile JWT 2.2 §7).
 *
 * <ul>
 *   <li>{@link #getUserPrincipal()} → the {@code JsonWebToken} itself (which is a {@link Principal}).</li>
 *   <li>{@link #isUserInRole(String)} → true if the role is present in the {@code groups} claim.</li>
 *   <li>{@link #getAuthenticationScheme()} → {@value #MP_JWT}.</li>
 * </ul>
 */
public final class JwtSecurityContext implements SecurityContext {

    /** Authentication scheme exposed for a MicroProfile JWT token. */
    public static final String MP_JWT = "MP-JWT";

    private final JsonWebToken token;
    private final boolean secure;

    public JwtSecurityContext(JsonWebToken token, boolean secure) {
        this.token = token;
        this.secure = secure;
    }

    @Override
    public Principal getUserPrincipal() {
        return token;
    }

    @Override
    public boolean isUserInRole(String role) {
        Set<String> groups = token.getGroups();
        return groups != null && groups.contains(role);
    }

    @Override
    public boolean isSecure() {
        return secure;
    }

    @Override
    public String getAuthenticationScheme() {
        return MP_JWT;
    }
}
