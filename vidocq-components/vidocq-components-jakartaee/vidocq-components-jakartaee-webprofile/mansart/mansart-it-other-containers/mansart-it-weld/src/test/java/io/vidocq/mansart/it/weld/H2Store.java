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
package io.vidocq.mansart.it.weld;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;

/** The application's data source: an in-memory H2 database, produced as a plain CDI bean. */
@ApplicationScoped
public class H2Store {

    static final String URL = "jdbc:h2:mem:mansart-it-weld;DB_CLOSE_DELAY=-1";

    @Produces
    @Singleton
    public DataSource dataSource() {
        return h2();
    }

    static JdbcDataSource h2() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(URL);
        ds.setUser("sa");
        return ds;
    }
}
