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
package io.vidocq.kerckhoffs.cdi;

import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import jakarta.security.enterprise.credential.UsernamePasswordCredential;
import jakarta.security.enterprise.identitystore.CredentialValidationResult;
import jakarta.security.enterprise.identitystore.CredentialValidationResult.Status;
import jakarta.security.enterprise.identitystore.IdentityStoreHandler;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The identity stores of an application reach the {@code IdentityStoreHandler} through CDI
 * (Jakarta Security 4.0, sections 3.2.4 and 3.4.3). The negative outcomes come first.
 */
class IdentityStoreCdiTest {

    private static VaubanContainer start(Class<?>... beans) {
        VaubanContainerBuilder builder = VaubanContainer.builder().addBeanClass(KerckhoffsExtension.class)
                .addBeanClass(IdentityStoreStartCheck.class);
        for (Class<?> bean : beans) {
            builder.addBeanClass(bean);
        }
        return builder.build();
    }

    private static CredentialValidationResult validate(VaubanContainer container, String caller, String password) {
        return container.select(IdentityStoreHandler.class).validate(new UsernamePasswordCredential(caller, password));
    }

    @Test
    void a_wrong_password_is_refused() {
        try (var container = start(MemoryApplication.class)) {
            assertEquals(Status.INVALID, validate(container, "reza", "wrong").getStatus());
        }
    }

    @Test
    void an_unknown_caller_is_refused() {
        try (var container = start(MemoryApplication.class)) {
            assertEquals(Status.INVALID, validate(container, "nobody", "secret1").getStatus());
        }
    }

    @Test
    void an_application_without_identity_store_validates_nobody() {
        try (var container = start()) {
            assertEquals(Status.NOT_VALIDATED, validate(container, "reza", "secret1").getStatus());
        }
    }

    @Test
    void a_caller_of_the_declared_store_is_let_in_with_its_groups() {
        try (var container = start(MemoryApplication.class)) {
            var result = validate(container, "reza", "secret1");

            assertEquals(Status.VALID, result.getStatus());
            assertEquals("reza", result.getCallerPrincipal().getName());
            assertEquals(Set.of("foo", "bar"), result.getCallerGroups());
        }
    }

    @Test
    void every_declared_store_takes_part_in_the_chain() {
        try (var container = start(MemoryApplication.class, SecondMemoryApplication.class)) {
            assertEquals(Status.VALID, validate(container, "alex", "secret2").getStatus());
            var second = validate(container, "werner", "secret4");

            assertEquals(Status.VALID, second.getStatus());
            assertEquals(Set.of("bar"), second.getCallerGroups());
        }
    }

    @Test
    void an_invalid_definition_stops_the_start() {
        assertThrows(RuntimeException.class, () -> start(BrokenApplication.class));
    }
}
