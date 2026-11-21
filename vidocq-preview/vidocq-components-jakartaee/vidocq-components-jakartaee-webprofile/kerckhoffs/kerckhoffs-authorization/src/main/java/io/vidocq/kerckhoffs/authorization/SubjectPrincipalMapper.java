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

import jakarta.security.jacc.PrincipalMapper;

import javax.security.auth.Subject;

import java.security.Principal;
import java.util.HashSet;
import java.util.Set;

/** The {@link PrincipalMapper} of the subjects Kerckhoffs builds: a {@link CallerPrincipal} and its {@link RolePrincipal}s. */
public final class SubjectPrincipalMapper implements PrincipalMapper {

    @Override
    public Principal getCallerPrincipal(Subject subject) {
        for (Principal principal : subject.getPrincipals()) {
            if (principal instanceof CallerPrincipal caller) {
                return caller;
            }
        }
        return null;
    }

    @Override
    public Set<String> getMappedRoles(Subject subject) {
        Set<String> roles = new HashSet<>();
        for (Principal principal : subject.getPrincipals()) {
            if (principal instanceof RolePrincipal role) {
                roles.add(role.getName());
            }
        }
        return roles;
    }
}
