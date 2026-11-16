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
package io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.live;

import jakarta.persistence.EntityManagerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The persistence units the Noether Persistence extension started, for its {@code -dev} panel only. They are
 * published at the end of {@code onStart} and cleared first thing in {@code onStop}, so that a dev reload never lets
 * the panel reach the previous boot's factories. This package is exported to the panel's module alone.
 */
public final class NoetherPersistenceLive {

    private static final AtomicReference<List<Unit>> UNITS = new AtomicReference<>(List.of());

    private NoetherPersistenceLive() {
    }

    /**
     * A unit of the running boot.
     *
     * @param name         its name
     * @param jta          whether its transactions are JTA
     * @param pool         the Mansart pool it names, or {@code null} for the application's default pool
     * @param schemaAction its {@code jakarta.persistence.schema-generation.database.action}, {@code none} by default
     * @param factory      its factory, for this boot only
     */
    public record Unit(String name, boolean jta, String pool, String schemaAction, EntityManagerFactory factory) {

        public Unit {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(factory, "factory");
        }
    }

    /** The units of the running boot, in the order of their names; empty before the start and once stopped. */
    public static List<Unit> units() {
        return UNITS.get();
    }

    /** Publishes {@code units}, an immutable copy, as the running boot's. */
    public static void publish(List<Unit> units) {
        UNITS.set(List.copyOf(units));
    }

    /** Forgets the units, at the stop of the boot. */
    public static void clear() {
        UNITS.set(List.of());
    }
}
