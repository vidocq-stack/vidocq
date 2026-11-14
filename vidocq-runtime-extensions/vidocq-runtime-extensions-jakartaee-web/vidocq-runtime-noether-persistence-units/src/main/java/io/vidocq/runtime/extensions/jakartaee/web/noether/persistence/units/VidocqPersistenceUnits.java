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
package io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.units;

import io.vidocq.noether.jpa.core.spi.PersistenceUnitDeclaration;
import io.vidocq.noether.jpa.core.spi.PersistenceUnitSource;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The persistence units of the {@code vidocq.persistence.*} keys ({@link UnitKeys}), as Noether reads them.
 *
 * <p>At start, the Noether Persistence extension hands over the keys of the running application, as its configuration
 * resolves them, {@code -D} and the environment included, and where its modules are. At build, where
 * {@code vidocq:generate} finds this source among the application's dependencies and nothing was handed over, the
 * keys are those of the application's {@code vidocq.properties} and {@code application.properties}: the beans the
 * build freezes are those of these units, and Noether's start check fails a start whose keys would make other beans.
 */
public final class VidocqPersistenceUnits implements PersistenceUnitSource {

    private static final AtomicReference<Application> STARTED = new AtomicReference<>();

    /** Called by the service loader. */
    public VidocqPersistenceUnits() {
        // the keys are handed over by start, for every instance
    }

    /**
     * The keys and the modules of the application that starts, until {@link #stop}.
     *
     * @param keys the configuration keys and their values; those outside {@code vidocq.persistence.} are ignored
     * @param modules where the application's modules are
     */
    public static void start(Map<String, String> keys, ModuleRoots modules) {
        STARTED.set(new Application(Map.copyOf(keys), Objects.requireNonNull(modules, "modules")));
    }

    /** Forgets the application that stopped. */
    public static void stop() {
        STARTED.set(null);
    }

    @Override
    public List<PersistenceUnitDeclaration> units(ClassLoader loader) {
        Application started = STARTED.get();
        if (started != null) {
            return UnitKeys.declarations(started.keys(), started.modules());
        }
        Deployment deployment = Deployment.of(loader);
        return UnitKeys.declarations(deployment.keys(), deployment);
    }

    private record Application(Map<String, String> keys, ModuleRoots modules) {
    }
}
