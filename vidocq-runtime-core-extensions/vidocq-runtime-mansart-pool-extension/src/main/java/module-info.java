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
/**
 * Vidocq extension that exposes a {@link io.vidocq.mansart.pool.core.MansartDataSource} as the
 * application's {@code @Default} JDBC {@link javax.sql.DataSource}, configured from
 * {@code vidocq.pool.*} properties.
 */
module io.vidocq.runtime.ext.mansart.pool {
    requires transitive io.vidocq.runtime.spi;
    requires io.vidocq.vauban.core;
    requires io.vidocq.mansart.pool.api;
    requires io.vidocq.mansart.pool.core;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires java.sql;        // javax.sql.DataSource

    // CDI scans MansartPoolHolder for @Produces; the package needs to be opened so the
    // generated bean factory can construct it.
    exports io.vidocq.runtime.ext.mansart.pool;
    opens   io.vidocq.runtime.ext.mansart.pool;

    provides io.vidocq.runtime.spi.VidocqExtension
            with io.vidocq.runtime.ext.mansart.pool.MansartPoolExtension;
}
