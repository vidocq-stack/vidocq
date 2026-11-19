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
import io.vidocq.noether.jpa.core.spi.UnitDescription;
import java.util.Map;

/**
 * The boot facts of a started persistence unit, kept for the startup report: names and counts, read once.
 *
 * @param name the unit's name
 * @param jta whether its transactions are JTA
 * @param pool the Mansart pool it names, or {@code null} for the application's default pool
 * @param schemaAction its {@code jakarta.persistence.schema-generation.database.action}, {@code none} by default
 * @param entities how many entities it maps
 * @param embeddables how many embeddable classes its entities use
 * @param namedQueries how many named queries it declares, JPQL and native
 * @param indexes how many indexes and unique constraints its tables declare
 */
record UnitFacts(String name, boolean jta, String pool, String schemaAction, int entities, int embeddables,
        int namedQueries, int indexes) {

    static final String SCHEMA_ACTION = "jakarta.persistence.schema-generation.database.action";

    /**
     * The facts of the unit {@code description} describes, declared with {@code transactions}, with the effective
     * {@code properties} of its factory.
     */
    static UnitFacts of(UnitDescription description, UnitTransactions transactions, Map<String, ?> properties) {
        String pool = blankToNull(transactions.jta() ? transactions.jtaDataSourceName()
                : transactions.nonJtaDataSourceName());
        Object action = properties.get(SCHEMA_ACTION);
        String schemaAction = action == null ? null : blankToNull(action.toString());
        return new UnitFacts(description.unitName(), transactions.jta(), pool,
                schemaAction == null ? "none" : schemaAction,
                description.entities().size(), description.embeddables().size(), description.namedQueries().size(),
                description.tables().stream().mapToInt(t -> t.indexes().size()).sum());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
