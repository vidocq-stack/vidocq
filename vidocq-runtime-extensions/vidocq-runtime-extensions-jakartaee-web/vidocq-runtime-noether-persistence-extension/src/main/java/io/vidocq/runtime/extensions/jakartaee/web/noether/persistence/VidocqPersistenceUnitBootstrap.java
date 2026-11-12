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

import io.vidocq.noether.jpa.cdi.PersistenceUnitBootstrap;
import io.vidocq.noether.jpa.core.spi.PersistenceDescriptors;
import io.vidocq.noether.jpa.core.spi.PersistenceDescriptors.UnitTransactions;
import io.vidocq.noether.jpa.core.spi.TransactionIntegration;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.TransactionManager;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * How {@code noether-jpa-cdi} creates the factory of a persistence unit on Vidocq: the unit, as its
 * {@code META-INF/persistence.xml} declares it, with the data sources {@link UnitDataSources} picks among the
 * application's Mansart pools, and, for a JTA unit, the transaction bridge {@code noether-jpa-cdi} hands over.
 */
@Dependent
public class VidocqPersistenceUnitBootstrap implements PersistenceUnitBootstrap {

    @Inject
    @Any
    Instance<DataSource> dataSources;

    @Inject
    Instance<TransactionManager> transactionManagers;

    @Override
    public EntityManagerFactory create(String unitName, TransactionIntegration transactions) {
        ClassLoader loader = loader();
        String name = unitName == null || unitName.isBlank() ? PersistenceDescriptors.defaultUnitName(loader)
                : unitName;
        UnitTransactions declared = PersistenceDescriptors.transactions(name, loader)
                .orElseThrow(() -> new PersistenceException("No META-INF/persistence.xml declares the persistence"
                        + " unit " + name));
        Map<String, Object> properties = new HashMap<>(UnitDataSources.properties(name, declared, new Pools(),
                pool -> new EnlistingDataSource(pool, transactionManager(name))));
        if (declared.jta()) {
            properties.put(TransactionIntegration.PROPERTY, transactions);
        }
        return Persistence.createEntityManagerFactory(name, properties);
    }

    private TransactionManager transactionManager(String unit) {
        Instance<TransactionManager> manager = transactionManagers;
        if (!manager.isResolvable()) {
            throw new PersistenceException("Persistence unit " + unit + " is JTA, and the application has no"
                    + " TransactionManager bean to enlist its connections in: add the mansart-transactions"
                    + " extension (`vidocq extension add mansart-transactions`), or make the unit RESOURCE_LOCAL");
        }
        return manager.get();
    }

    private static ClassLoader loader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : VidocqPersistenceUnitBootstrap.class.getClassLoader();
    }

    /** The application's Mansart pools, as CDI resolves them. */
    private final class Pools implements UnitDataSources.Lookup {
        @Override
        public Optional<DataSource> named(String name) {
            return resolve(dataSources.select(NamedLiteral.of(name)), "@Named(\"" + name + "\")");
        }

        @Override
        public Optional<DataSource> byDefault() {
            return resolve(dataSources.select(Default.Literal.INSTANCE), "@Default");
        }

        private static Optional<DataSource> resolve(Instance<DataSource> candidates, String qualifier) {
            if (candidates.isAmbiguous()) {
                throw new PersistenceException("Several javax.sql.DataSource beans are " + qualifier
                        + ": a persistence unit needs exactly one");
            }
            return candidates.isUnsatisfied() ? Optional.empty() : Optional.of(candidates.get());
        }
    }
}
