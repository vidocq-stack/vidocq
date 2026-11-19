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
package io.vidocq.kerckhoffs.authorization;

import jakarta.security.jacc.Policy;
import jakarta.security.jacc.WebResourcePermission;

import javax.security.auth.Subject;

import java.security.Permission;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.Principal;
import java.util.HashSet;
import java.util.Set;

/**
 * The default {@link Policy}: a {@link WebResourcePermission} is granted when the security constraints of the
 * application let the caller of the subject reach the resource. Every other permission is refused.
 *
 * <p>It reads the constraints bound to {@link #CONSTRAINTS} by the container around the check; with none bound it
 * grants nothing, so a check made outside a request fails closed.
 */
public final class ConstraintPolicy implements Policy {

    /** The constraints of the request being checked. */
    public static final ScopedValue<WebConstraints> CONSTRAINTS = ScopedValue.newInstance();

    private static final String DEFAULT_METHOD = "GET";

    @Override
    public boolean implies(Permission permission, Subject subject) {
        if (!(permission instanceof WebResourcePermission web) || !CONSTRAINTS.isBound()) {
            return false;
        }
        String actions = web.getActions();
        String method = actions == null || actions.isBlank() ? DEFAULT_METHOD : actions;
        String caller = null;
        Set<String> roles = new HashSet<>();
        for (Principal principal : subject.getPrincipals()) {
            switch (principal) {
                case CallerPrincipal named -> caller = named.getName();
                case RolePrincipal role -> roles.add(role.getName());
                default -> { /* another principal says nothing about the caller or the roles */ }
            }
        }
        return CONSTRAINTS.get().permits(web.getName(), method, caller, caller == null ? Set.of() : roles);
    }

    @Override
    public PermissionCollection getPermissionCollection(Subject subject) {
        return new Permissions();
    }
}
