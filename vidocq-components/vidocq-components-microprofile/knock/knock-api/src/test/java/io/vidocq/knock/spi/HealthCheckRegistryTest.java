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
package io.vidocq.knock.spi;

import org.eclipse.microprofile.health.HealthCheck;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD — the default methods of {@link HealthCheckRegistry}, as a registry that implements only
 * {@link HealthCheckRegistry#getChecks(ProbeType)} sees them.
 */
class HealthCheckRegistryTest {

    /**
     * Twenty checks, each of its own class (one lambda expression each), so that each gets its own name.
     * The answers are never read: listing never calls a check.
     */
    private static final List<HealthCheck> CHECKS = List.of(
            () -> null, () -> null, () -> null, () -> null, () -> null,
            () -> null, () -> null, () -> null, () -> null, () -> null,
            () -> null, () -> null, () -> null, () -> null, () -> null,
            () -> null, () -> null, () -> null, () -> null, () -> null);

    /** A registry that lists {@link #CHECKS} for every concrete probe and relies on the defaults for the rest. */
    private static final class ListedRegistry implements HealthCheckRegistry {

        @Override
        public void register(ProbeType type, String name, HealthCheck check) {
            throw new UnsupportedOperationException("read-only");
        }

        @Override
        public void unregister(String name) {
            throw new UnsupportedOperationException("read-only");
        }

        @Override
        public List<HealthCheck> getChecks(ProbeType type) {
            return CHECKS;
        }
    }

    /**
     * The default named view keeps the order of {@link HealthCheckRegistry#getChecks(ProbeType)}, so the
     * aggregated response lists the checks of such a registry the same at every start ({@code Map.copyOf}
     * iterated in an order salted per JVM, BUG-20261114-01).
     */
    @Test
    void named_checks_keep_the_order_of_the_checks() {
        Map<String, HealthCheck> named = new ListedRegistry().getNamedChecks(ProbeType.LIVENESS);

        assertEquals(CHECKS, List.copyOf(named.values()));
        assertThrows(UnsupportedOperationException.class, named::clear);
    }

    @Test
    void check_names_keep_the_order_of_the_checks() {
        List<String> expected = CHECKS.stream().map(check -> check.getClass().getName()).toList();

        assertEquals(expected, List.copyOf(new ListedRegistry().getCheckNames(ProbeType.READINESS)));
        assertEquals(expected, List.copyOf(new ListedRegistry().getCheckNames(ProbeType.ALL)),
                "the same checks under the three probes are named once");
    }

    @Test
    void all_has_no_named_checks_of_its_own() {
        HealthCheckRegistry registry = new ListedRegistry();

        assertThrows(IllegalArgumentException.class, () -> registry.getNamedChecks(ProbeType.ALL));
    }
}
