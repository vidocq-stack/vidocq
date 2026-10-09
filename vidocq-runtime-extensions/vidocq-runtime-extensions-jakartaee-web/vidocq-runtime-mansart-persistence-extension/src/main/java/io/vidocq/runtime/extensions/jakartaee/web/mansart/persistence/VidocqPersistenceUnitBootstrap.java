/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.extensions.jakartaee.web.mansart.persistence;

import io.vidocq.mansart.jpa.cdi.PersistenceUnitBootstrap;
import io.vidocq.mansart.jpa.core.spi.TransactionIntegration;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.Persistence;
import jakarta.persistence.PersistenceException;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Supplies container resources to Mansart's CDI-owned persistence-unit factory.
 *
 * <p>A managed {@code DataSource} is passed through as the original object. When there is no
 * CDI-managed default data source, the persistence unit's configured JDBC properties remain in
 * effect. The transaction integration belongs to the persistence-unit CDI factory.</p>
 */
@Dependent
public class VidocqPersistenceUnitBootstrap implements PersistenceUnitBootstrap {
    @Inject
    @Any
    Instance<DataSource> dataSources;

    @Override
    public jakarta.persistence.EntityManagerFactory create(
            String unitName, TransactionIntegration transactions) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = getClass().getClassLoader();
        List<PersistenceUnitDiscovery.Unit> units = PersistenceUnitDiscovery.discoverUnits(loader);
        PersistenceUnitDiscovery.Unit unit = resolveUnit(units, unitName);
        unitName = unit.name();

        boolean jta = unit.transactionType().equalsIgnoreCase("JTA");
        String sourceName = jta ? unit.jtaDataSource() : unit.nonJtaDataSource();
        String sourceProperty = jta
                ? "jakarta.persistence.jtaDataSource" : "jakarta.persistence.nonJtaDataSource";

        Map<String, Object> properties = new HashMap<>();
        properties.put(TransactionIntegration.PROPERTY, transactions);
        Instance<DataSource> selected = sourceName == null || sourceName.isBlank()
                ? dataSources.select(Default.Literal.INSTANCE)
                : dataSources.select(new NamedLiteral(sourceName));
        if (selected.isAmbiguous()) {
            throw new PersistenceException("Persistence unit " + unitName
                    + " resolves to an ambiguous CDI DataSource");
        }
        if (selected.isUnsatisfied()) {
            if (sourceName != null && !sourceName.isBlank()) {
                throw new PersistenceException("Persistence unit " + unitName
                        + " names DataSource " + sourceName + " but no matching CDI DataSource exists");
            }
        } else {
            properties.put(sourceProperty, selected.get());
        }
        return Persistence.createEntityManagerFactory(unitName, properties);
    }

    static PersistenceUnitDiscovery.Unit resolveUnit(List<PersistenceUnitDiscovery.Unit> units, String unitName) {
        if (unitName == null || unitName.isBlank()) {
            if (units.size() != 1) {
                throw new PersistenceException("Default persistence injection requires exactly one named "
                        + "persistence unit; found " + units.stream().map(PersistenceUnitDiscovery.Unit::name).toList());
            }
            return units.getFirst();
        }
        List<PersistenceUnitDiscovery.Unit> matches = units.stream()
                .filter(candidate -> candidate.name().equals(unitName))
                .toList();
        if (matches.size() != 1) {
            throw new PersistenceException("Expected one persistence unit named " + unitName
                    + " but discovered " + matches.size());
        }
        return matches.getFirst();
    }

    private static final class NamedLiteral extends AnnotationLiteral<Named> implements Named {
        private final String name;

        private NamedLiteral(String name) {
            this.name = name;
        }

        @Override
        public String value() {
            return name;
        }
    }
}
