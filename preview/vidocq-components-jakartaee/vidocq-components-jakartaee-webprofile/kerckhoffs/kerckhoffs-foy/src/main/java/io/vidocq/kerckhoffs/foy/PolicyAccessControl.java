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

import io.vidocq.foy.spi.security.AccessControl;
import io.vidocq.foy.spi.security.Identity;
import io.vidocq.kerckhoffs.authorization.CallerPrincipal;
import io.vidocq.kerckhoffs.authorization.ConstraintPolicy;
import io.vidocq.kerckhoffs.authorization.ConstraintPolicyFactory;
import io.vidocq.kerckhoffs.authorization.RolePrincipal;
import jakarta.security.jacc.PolicyFactory;
import jakarta.security.jacc.WebResourcePermission;

import javax.security.auth.Subject;

import java.util.Objects;

/**
 * Places the {@code Policy} of Jakarta Authorization in service on the access hook of Foy (Servlet 6.1 section 13,
 * Jakarta Authorization 3.0): each request is asked of the policy of the {@link PolicyFactory} as a
 * {@link WebResourcePermission}, for the subject of the caller. The default policy answers with the security
 * constraints of the application; a policy the application set decides in its place and may delegate to it.
 */
public final class PolicyAccessControl implements AccessControl {

    /** Makes sure a policy factory is in service: Kerckhoffs' own unless the platform has one. */
    public PolicyAccessControl() {
        ConstraintPolicyFactory.install();
    }

    @Override
    public boolean permits(String path, String method, Identity caller, Constraints constraints) {
        Objects.requireNonNull(constraints, "constraints");
        var policy = PolicyFactory.getPolicyFactory().getPolicy();
        var permission = new WebResourcePermission(path, method);
        var subject = subjectOf(caller);
        try {
            return ScopedValue.where(ConstraintPolicy.CONSTRAINTS,
                            (p, m, name, roles) -> constraints.permits(p, m,
                                    name == null ? null : Identity.of(name, roles)))
                    .call(() -> policy.implies(permission, subject));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("the policy failed to answer " + permission, e);
        }
    }

    private static Subject subjectOf(Identity caller) {
        var subject = new Subject();
        if (caller != null) {
            subject.getPrincipals().add(new CallerPrincipal(caller.name()));
            caller.roles().forEach(role -> subject.getPrincipals().add(new RolePrincipal(role)));
        }
        return subject;
    }
}
