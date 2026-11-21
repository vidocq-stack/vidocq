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
import io.vidocq.kerckhoffs.authorization.ConstraintPolicy;
import io.vidocq.kerckhoffs.authorization.ConstraintPolicyFactory;
import jakarta.security.jacc.Policy;
import jakarta.security.jacc.PolicyFactory;
import jakarta.security.jacc.WebResourcePermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.security.auth.Subject;

import java.security.Permission;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The access decision of Foy goes to the policy of Jakarta Authorization, as a {@code WebResourcePermission}. */
class PolicyAccessControlTest {

    private final PolicyAccessControl control = new PolicyAccessControl();
    private Policy original;

    @BeforeEach
    void keepThePolicy() {
        original = ConstraintPolicyFactory.install().getPolicy();
    }

    @AfterEach
    void restoreThePolicy() {
        PolicyFactory.getPolicyFactory().setPolicy(original);
    }

    /** Foy's constraints: only an admin reaches {@code /admin/*}. */
    private static final io.vidocq.foy.spi.security.AccessControl.Constraints CONSTRAINTS = (path, method, caller) ->
            !path.startsWith("/admin/") || (caller != null && caller.hasRole("admin"));

    @Test
    void theDefaultPolicyAnswersWhatTheConstraintsSay() {
        assertTrue(control.permits("/admin/x", "GET", Identity.of("alice", Set.of("admin")), CONSTRAINTS));
        assertFalse(control.permits("/admin/x", "GET", Identity.of("bob", Set.of("user")), CONSTRAINTS));
        assertFalse(control.permits("/admin/x", "GET", null, CONSTRAINTS));
        assertTrue(control.permits("/open", "POST", null, CONSTRAINTS));
    }

    /** A policy of an application, as the TCK registers one: it grants a path the constraints would refuse. */
    private static final class Custom implements Policy {
        final List<Permission> asked = new ArrayList<>();
        final List<Subject> subjects = new ArrayList<>();
        private final Policy original;

        Custom(Policy original) { this.original = original; }

        @Override public boolean implies(Permission permission, Subject subject) {
            asked.add(permission);
            subjects.add(subject);
            return permission.getName().startsWith("/special/") || original.implies(permission, subject);
        }

        @Override public PermissionCollection getPermissionCollection(Subject subject) { return new Permissions(); }
    }

    @Test
    void aPolicyTheApplicationSetIsTheOneThatDecidesAndItCanDelegateToTheDefaultOne() {
        var custom = new Custom(PolicyFactory.getPolicyFactory().getPolicy());
        PolicyFactory.getPolicyFactory().setPolicy(custom);

        assertTrue(control.permits("/special/x", "GET", null, CONSTRAINTS), "granted by the application");
        assertFalse(control.permits("/admin/x", "GET", null, CONSTRAINTS), "the original policy still refuses");
        assertTrue(control.permits("/admin/x", "GET", Identity.of("alice", Set.of("admin")), CONSTRAINTS));
        assertEquals(new WebResourcePermission("/special/x", "GET"), custom.asked.getFirst());
    }

    @Test
    void theSubjectCarriesTheCallerAndItsRoles() {
        var custom = new Custom(new ConstraintPolicy());
        PolicyFactory.getPolicyFactory().setPolicy(custom);

        control.permits("/open", "GET", Identity.of("alice", Set.of("admin", "user")), CONSTRAINTS);

        var names = custom.subjects.getFirst().getPrincipals().stream().map(p -> p.getName()).sorted().toList();
        assertEquals(List.of("admin", "alice", "user"), names);
    }
}
