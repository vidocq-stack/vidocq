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
package io.vidocq.runtime.extensions.jakartaee.web.noether.persistence;

import io.vidocq.noether.jpa.core.spi.PersistenceDescriptors.UnitTransactions;
import jakarta.persistence.PersistenceException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;

/**
 * The data sources a persistence unit is handed, from the Mansart pools of the application.
 *
 * <ul>
 *   <li>A JTA unit takes the pool its {@code jta-data-source} names, {@code @Default} when it names none, wrapped so
 *       that its connections take part in the current transaction. Its {@code non-jta-data-source} is the pool that
 *       element names, else the same pool unwrapped: Noether allocates TABLE identifiers there, in transactions of
 *       their own, which a rollback of the caller must not undo.</li>
 *   <li>A RESOURCE_LOCAL unit takes the pool its {@code non-jta-data-source} names, {@code @Default} when it names
 *       none, as is. Without a {@code @Default} pool it keeps the connection settings of its descriptor.</li>
 * </ul>
 *
 * A named pool that does not exist refuses the unit, with the key that would create it.
 */
final class UnitDataSources {

    static final String JTA_DATA_SOURCE = "jakarta.persistence.jtaDataSource";
    static final String NON_JTA_DATA_SOURCE = "jakarta.persistence.nonJtaDataSource";

    /** The pools of the application: the {@code @Named} ones and the {@code @Default} one. */
    interface Lookup {
        Optional<DataSource> named(String name);

        Optional<DataSource> byDefault();
    }

    private UnitDataSources() {
    }

    /**
     * The data source properties of the unit {@code unit}, to pass to {@code Persistence.createEntityManagerFactory}.
     *
     * @param enlisting wraps a pool for a JTA unit
     * @throws PersistenceException if the unit names a pool that does not exist, or is JTA without any pool
     */
    static Map<String, Object> properties(String unit, UnitTransactions transactions, Lookup pools,
            UnaryOperator<DataSource> enlisting) {
        Map<String, Object> properties = new HashMap<>();
        String nonJtaName = blankToNull(transactions.nonJtaDataSourceName());
        if (transactions.jta()) {
            String jtaName = blankToNull(transactions.jtaDataSourceName());
            DataSource pool = jtaName != null ? named(unit, jtaName, pools)
                    : pools.byDefault().orElseThrow(() -> noDefaultPool(unit));
            properties.put(JTA_DATA_SOURCE, enlisting.apply(pool));
            properties.put(NON_JTA_DATA_SOURCE, nonJtaName != null ? named(unit, nonJtaName, pools) : pool);
        } else if (nonJtaName != null) {
            properties.put(NON_JTA_DATA_SOURCE, named(unit, nonJtaName, pools));
        } else {
            pools.byDefault().ifPresent(pool -> properties.put(NON_JTA_DATA_SOURCE, pool));
        }
        return properties;
    }

    private static DataSource named(String unit, String name, Lookup pools) {
        return pools.named(name).orElseThrow(() -> new PersistenceException("Persistence unit " + unit
                + " names the data source '" + name + "', and the application has no Mansart pool of that name:"
                + " declare it with @VidocqDataSources(\"" + name + "\") and set vidocq.pool." + name + ".url"
                + " (vidocq.properties, -D or the environment)"));
    }

    private static PersistenceException noDefaultPool(String unit) {
        return new PersistenceException("Persistence unit " + unit + " is JTA and names no jta-data-source, and the"
                + " application has no default Mansart pool: set vidocq.pool.url (vidocq.properties, -D or the"
                + " environment), or name a pool in the unit's jta-data-source");
    }

    private static String blankToNull(String name) {
        return name == null || name.isBlank() ? null : name.strip();
    }
}
