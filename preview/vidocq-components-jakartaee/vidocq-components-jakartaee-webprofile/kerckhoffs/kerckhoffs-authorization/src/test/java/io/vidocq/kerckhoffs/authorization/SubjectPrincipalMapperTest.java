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

import jakarta.security.jacc.PolicyContext;
import jakarta.security.jacc.PrincipalMapper;
import org.junit.jupiter.api.Test;

import javax.security.auth.Subject;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A policy asks the {@link PrincipalMapper} of the policy context who the caller of a subject is. */
class SubjectPrincipalMapperTest {

    private final PrincipalMapper mapper = new SubjectPrincipalMapper();

    private static Subject alice() {
        var subject = new Subject();
        subject.getPrincipals().add(new CallerPrincipal("alice"));
        subject.getPrincipals().add(new RolePrincipal("admin"));
        subject.getPrincipals().add(new RolePrincipal("user"));
        return subject;
    }

    @Test
    void theCallerAndTheRolesAreThoseOfTheSubject() {
        assertEquals(new CallerPrincipal("alice"), mapper.getCallerPrincipal(alice()));
        assertEquals(Set.of("admin", "user"), mapper.getMappedRoles(alice()));
    }

    @Test
    void aSubjectWithoutCallerHasNoCallerAndNoRole() {
        assertNull(mapper.getCallerPrincipal(new Subject()));
        assertTrue(mapper.getMappedRoles(new Subject()).isEmpty());
    }

    @Test
    void thePolicyContextAnswersTheMapperOnceTheFactoryIsInstalled() throws Exception {
        ConstraintPolicyFactory.install();

        PrincipalMapper found = PolicyContext.getContext(PolicyContext.PRINCIPAL_MAPPER);

        assertEquals(new CallerPrincipal("alice"), found.getCallerPrincipal(alice()));
    }
}
