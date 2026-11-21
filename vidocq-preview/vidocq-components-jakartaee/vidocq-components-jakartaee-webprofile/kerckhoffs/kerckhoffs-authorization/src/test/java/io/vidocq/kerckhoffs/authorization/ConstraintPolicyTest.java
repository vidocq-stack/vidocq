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
import jakarta.security.jacc.PolicyFactory;
import jakarta.security.jacc.WebResourcePermission;
import jakarta.security.jacc.WebUserDataPermission;
import org.junit.jupiter.api.Test;

import javax.security.auth.Subject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The default policy of Jakarta Authorization 3.0: a web resource permission is what the constraints say. */
class ConstraintPolicyTest {

    private record Asked(String path, String method, String caller, Set<String> roles) { }

    private final List<Asked> asked = new ArrayList<>();
    private final ConstraintPolicy policy = new ConstraintPolicy();

    /** Constraints that let only {@code alice} (role {@code admin}) reach {@code /admin/*}. */
    private final WebConstraints constraints = (path, method, caller, roles) -> {
        asked.add(new Asked(path, method, caller, roles));
        return !path.startsWith("/admin/") || (caller != null && roles.contains("admin"));
    };

    private static Subject subject(String caller, String... roles) {
        var subject = new Subject();
        if (caller != null) {
            subject.getPrincipals().add(new CallerPrincipal(caller));
            for (String role : roles) subject.getPrincipals().add(new RolePrincipal(role));
        }
        return subject;
    }

    private boolean implies(String path, String method, Subject subject) throws Exception {
        return ScopedValue.where(ConstraintPolicy.CONSTRAINTS, constraints)
                .call(() -> policy.implies(new WebResourcePermission(path, method), subject));
    }

    @Test
    void aCallerWithTheRoleOfTheConstraintReachesTheResource() throws Exception {
        assertTrue(implies("/admin/x", "GET", subject("alice", "admin")));
        assertEquals(new Asked("/admin/x", "GET", "alice", Set.of("admin")), asked.getFirst());
    }

    @Test
    void aCallerWithoutTheRoleIsRefused() throws Exception {
        assertFalse(implies("/admin/x", "GET", subject("bob", "user")));
    }

    @Test
    void nobodyIsAnAnonymousCallerWithNoRole() throws Exception {
        assertTrue(implies("/open", "POST", subject(null)));
        assertEquals(new Asked("/open", "POST", null, Set.of()), asked.getFirst());
        assertFalse(implies("/admin/x", "GET", subject(null)));
    }

    @Test
    void aPermissionWithoutAnActionIsAskedForTheGetMethod() throws Exception {
        ScopedValue.where(ConstraintPolicy.CONSTRAINTS, constraints)
                .call(() -> policy.implies(new WebResourcePermission("/open", (String) null), subject(null)));

        assertEquals("GET", asked.getFirst().method());
    }

    @Test
    void withoutConstraintsToConsultNothingIsPermitted() {
        assertFalse(policy.implies(new WebResourcePermission("/open", "GET"), subject(null)));
    }

    @Test
    void aPermissionThatIsNotAWebResourceIsNotGranted() throws Exception {
        var granted = ScopedValue.where(ConstraintPolicy.CONSTRAINTS, constraints)
                .call(() -> policy.implies(new WebUserDataPermission("/open", "GET"), subject(null)));

        assertFalse(granted);
        assertTrue(asked.isEmpty());
    }

    @Test
    void theDefaultPolicyGrantsNoPermissionCollection() {
        assertFalse(policy.getPermissionCollection(subject("alice")).elements().hasMoreElements());
    }

    @Test
    void thePolicyFactoryAnswersTheDefaultPolicyUntilAnotherIsSet() {
        var factory = new ConstraintPolicyFactory();
        assertTrue(factory.getPolicy() instanceof ConstraintPolicy);
        assertSame(factory.getPolicy(), factory.getPolicy("any-context"));

        Policy replaced = new ConstraintPolicy();
        factory.setPolicy(replaced);
        assertSame(replaced, factory.getPolicy());
        assertSame(replaced, factory.getPolicy("any-context"));
    }

    @Test
    void aPolicyOfOneContextDoesNotReplaceTheOthers() {
        var factory = new ConstraintPolicyFactory();
        Policy mine = new ConstraintPolicy();
        factory.setPolicy("mine", mine);

        assertSame(mine, factory.getPolicy("mine"));
        assertNotSame(mine, factory.getPolicy());
    }

    @Test
    void installingTheFactoryKeepsTheOneAlreadyThere() {
        PolicyFactory first = ConstraintPolicyFactory.install();

        assertNotNull(first);
        assertSame(first, ConstraintPolicyFactory.install());
        assertSame(first, PolicyFactory.getPolicyFactory());
    }
}
