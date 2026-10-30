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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool;

/**
 * The error of a pool that got no database: it names both ways out, the URL and the PostgreSQL dev service, and the
 * test listener a plain {@code mvn test} needs for the dev service (BUG-20261031-05, BUG-20261031-07).
 */
final class MissingDataSource {

    private MissingDataSource() {}

    /** For the {@code @Default} pool, the one of {@code vidocq.pool.url}. */
    static IllegalStateException forDefaultPool() {
        return new IllegalStateException("MansartPoolExtension did not publish a DataSource: "
                + waysOut("vidocq.pool.url", ""));
    }

    /** For the pool named {@code name}, the one of {@code vidocq.pool.<name>.url}. */
    static IllegalStateException forNamedPool(String name) {
        return new IllegalStateException("No DataSource registered for dataStore '" + name + "': "
                + waysOut("vidocq.pool." + name + ".url",
                        ", with " + name + " in vidocq.dev.postgres.datasources"));
    }

    private static String waysOut(String urlKey, String devServiceCondition) {
        return "set " + urlKey + " (vidocq.properties, -D or the environment), or let the PostgreSQL dev service"
                + " provide it" + devServiceCondition + ": `vidocq extension add mansart-pool` declares"
                + " vidocq-runtime-devservice-postgres and the PostgreSQL driver; under `mvn test`, the dev service"
                + " starts through vidocq-runtime-devservices-junit, in test scope. The dev services report says why"
                + " one did not start.";
    }
}
