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
package io.vidocq.noether.jpa.core.jdbc;

import io.vidocq.noether.jpa.core.bootstrap.Definitions;
import io.vidocq.noether.jpa.core.bootstrap.UnitSettings;
import jakarta.persistence.PersistenceUnitTransactionType;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

/**
 * The data sources a unit names, for a factory the application creates itself (Vidocq/vidocq#676): a name given by
 * {@code jta-data-source}, {@code non-jta-data-source} or their properties, for which no {@link DataSource} object
 * was passed, is looked up through JNDI (§8.2.1.5), as written, then under {@code java:comp/env} when it is not an
 * absolute {@code java:} name. A container that creates the factory hands the objects over itself (§9.6), and
 * nothing is looked up then.
 *
 * <p>A JTA unit also takes the non-JTA data source it names, which its TABLE generators allocate through: the
 * container enlists every connection of the JTA one in the transaction (BUG-20261111-04).
 *
 * <p>{@code java.naming} is optional ({@code requires static}): without it in the runtime image, or without a naming
 * context, no name is found and the unit is refused as before, with a message saying what to do.
 */
public final class NamedDataSources {

    private static final String ENVIRONMENT = "java:comp/env/";

    private NamedDataSources() {
    }

    /** {@code settings} with the data sources JNDI answers for the names of the unit, or {@code settings} itself. */
    public static UnitSettings resolve(UnitSettings settings) {
        return resolve(settings, NamedDataSources::jndi);
    }

    /** {@link #resolve(UnitSettings)}, looking the names up in {@code naming}. */
    static UnitSettings resolve(UnitSettings settings, Function<String, Optional<Object>> naming) {
        Map<String, Object> found = new HashMap<>();
        boolean jta = settings.transactionType() == PersistenceUnitTransactionType.JTA;
        if (jta) {
            find(settings, Definitions.JTA_DATA_SOURCE, settings.definition().jtaDataSourceName(), naming, found);
        }
        if (jta || !(settings.property(UnitSettings.DATA_SOURCE) instanceof DataSource)) {
            find(settings, Definitions.NON_JTA_DATA_SOURCE, settings.definition().nonJtaDataSourceName(), naming, found);
        }
        return found.isEmpty() ? settings : UnitSettings.of(settings.definition(), settings.properties(), found);
    }

    /** Puts under {@code key} the data source bound under the name the unit gives for it, unless an object was passed. */
    private static void find(UnitSettings settings, String key, String declared,
            Function<String, Optional<Object>> naming, Map<String, Object> found) {
        Object property = settings.property(key);
        if (property instanceof DataSource) {
            return;
        }
        String name = property instanceof String text ? text : declared;
        if (name == null || name.isBlank()) {
            return;
        }
        String written = name.strip();
        Optional<Object> bound = naming.apply(written);
        if (bound.isEmpty() && !written.startsWith("java:")) {
            bound = naming.apply(ENVIRONMENT + written);
        }
        bound.filter(DataSource.class::isInstance).ifPresent(dataSource -> found.put(key, dataSource));
    }

    /** What JNDI binds {@code name} to, or empty: no such name, no naming context, or no {@code java.naming}. */
    private static Optional<Object> jndi(String name) {
        try {
            return Jndi.lookup(name);
        } catch (NoClassDefFoundError _) {
            return Optional.empty();
        }
    }

    /** The only class that names {@code javax.naming}, so that its absence shows at the first lookup only. */
    private static final class Jndi {

        private Jndi() {
        }

        @SuppressWarnings("BanJNDI") // JNDI: the data source a unit names (jta-data-source), as Jakarta Persistence defines
        static Optional<Object> lookup(String name) {
            try {
                return Optional.ofNullable(InitialContext.doLookup(name));
            } catch (NamingException _) {
                return Optional.empty();
            }
        }
    }
}
