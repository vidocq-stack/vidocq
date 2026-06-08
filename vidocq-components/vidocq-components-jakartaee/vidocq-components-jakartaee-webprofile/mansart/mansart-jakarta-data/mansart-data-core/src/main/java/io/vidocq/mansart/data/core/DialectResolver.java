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
package io.vidocq.mansart.data.core;

import io.vidocq.mansart.data.dialect.Dialect;
import io.vidocq.mansart.data.dialect.DialectFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ServiceLoader;

/**
 * Discovers the appropriate {@link Dialect} for a given {@link DataSource} via
 * {@code ServiceLoader<DialectFactory>}. The first factory whose {@code supports(...)} returns
 * {@code true} for the data source's metadata wins.
 *
 * <p>The lookup queries {@link Connection#getMetaData()} once at bootstrap and caches the dialect.
 */
final class DialectResolver {

    private DialectResolver() {}

    static Dialect resolve(DataSource ds) {
        try (Connection c = ds.getConnection()) {
            var md = c.getMetaData();
            for (DialectFactory factory : ServiceLoader.load(DialectFactory.class)) {
                if (factory.supports(md)) return factory.create();
            }
            throw new MansartDataException("No DialectFactory accepts database product '"
                    + md.getDatabaseProductName() + "'. Add a mansart-data-dialect-* JAR to the module path.");
        } catch (java.sql.SQLException e) {
            throw new MansartDataException("Failed to detect dialect from DataSource", e);
        }
    }
}
