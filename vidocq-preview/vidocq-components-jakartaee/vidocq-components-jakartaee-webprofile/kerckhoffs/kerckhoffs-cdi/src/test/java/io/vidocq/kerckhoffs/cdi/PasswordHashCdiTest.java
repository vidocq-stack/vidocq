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
import jakarta.security.enterprise.identitystore.Pbkdf2PasswordHash;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The built-in {@link Pbkdf2PasswordHash} is a bean every application can inject (Jakarta Security 4.0, the
 * {@code Pbkdf2PasswordHash} interface), even one that declares no identity store (BUG-20261123-60).
 */
class PasswordHashCdiTest {

    private static VaubanContainer start() {
        return VaubanContainer.builder().addBeanClass(KerckhoffsExtension.class)
                .addBeanClass(IdentityStoreStartCheck.class).build();
    }

    @Test
    void a_wrong_password_does_not_verify() {
        try (var container = start()) {
            var hash = container.select(Pbkdf2PasswordHash.class);
            String stored = hash.generate("secret1".toCharArray());

            assertFalse(hash.verify("wrong".toCharArray(), stored));
        }
    }

    @Test
    void the_injected_hash_verifies_what_it_generated() {
        try (var container = start()) {
            var hash = container.select(Pbkdf2PasswordHash.class);
            String stored = hash.generate("secret1".toCharArray());

            assertTrue(stored.startsWith("PBKDF2WithHmacSHA256:2048:"), stored);
            assertTrue(hash.verify("secret1".toCharArray(), stored));
        }
    }

    /** Each injection point is configured on its own ({@code initialize}): the bean is {@code @Dependent}. */
    @Test
    void each_injection_gets_its_own_configuration() {
        try (var container = start()) {
            var configured = container.select(Pbkdf2PasswordHash.class);
            configured.initialize(Map.of("Pbkdf2PasswordHash.Algorithm", "PBKDF2WithHmacSHA512",
                    "Pbkdf2PasswordHash.Iterations", "3072"));
            var other = container.select(Pbkdf2PasswordHash.class);

            assertNotSame(configured, other);
            assertTrue(configured.generate("secret1".toCharArray()).startsWith("PBKDF2WithHmacSHA512:3072:"));
            assertTrue(other.generate("secret1".toCharArray()).startsWith("PBKDF2WithHmacSHA256:2048:"));
        }
    }
}
