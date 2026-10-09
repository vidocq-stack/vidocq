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

import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersistenceUnitDiscoveryTest {

    @Test
    void discoversNamedUnitsWithoutFetchingExternalEntities() {
        String descriptor = """
                <?xml version="1.0"?>
                <!DOCTYPE persistence SYSTEM "https://invalid.example/persistence.dtd">
                <persistence>
                    <persistence-unit name="orders"/>
                    <persistence-unit name="audit"/>
                </persistence>
                """;

        assertThrows(IllegalStateException.class, () -> PersistenceUnitDiscovery.parse(
                new ByteArrayInputStream(descriptor.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void discoversAllNamedUnitsInDeclarationOrder() {
        String descriptor = """
                <?xml version="1.0"?>
                <persistence>
                    <persistence-unit name="orders"/>
                    <persistence-unit name="audit"/>
                </persistence>
                """;

        assertEquals(List.of("orders", "audit"), PersistenceUnitDiscovery.parse(
                new ByteArrayInputStream(descriptor.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void rejectsUnnamedAndDuplicateUnits() {
        String unnamed = "<persistence><persistence-unit/></persistence>";
        String duplicate = """
                <persistence>
                    <persistence-unit name="orders"/>
                    <persistence-unit name="orders"/>
                </persistence>
                """;

        assertThrows(IllegalStateException.class, () -> PersistenceUnitDiscovery.parse(
                new ByteArrayInputStream(unnamed.getBytes(StandardCharsets.UTF_8))));
        assertThrows(IllegalStateException.class, () -> PersistenceUnitDiscovery.parse(
                new ByteArrayInputStream(duplicate.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void readsDataSourceAndDefaultsItsTransactionTypeToJta() {
        String descriptor = """
                <persistence>
                    <persistence-unit name="managed">
                        <jta-data-source>jdbc/managed</jta-data-source>
                    </persistence-unit>
                </persistence>
                """;

        var unit = PersistenceUnitDiscovery.parseUnits(
                new ByteArrayInputStream(descriptor.getBytes(StandardCharsets.UTF_8))).getFirst();

        assertEquals("managed", unit.name());
        assertEquals("JTA", unit.transactionType());
        assertEquals("jdbc/managed", unit.jtaDataSource());
    }

    @Test
    void defaultsUnitsWithoutJtaDataSourcesToResourceLocal() {
        String descriptor = "<persistence><persistence-unit name=\"local\"/></persistence>";

        var unit = PersistenceUnitDiscovery.parseUnits(
                new ByteArrayInputStream(descriptor.getBytes(StandardCharsets.UTF_8))).getFirst();

        assertEquals("RESOURCE_LOCAL", unit.transactionType());
    }

    @Test
    void requiresAnExplicitUnitWhenTheDeploymentHasMoreThanOne() {
        var units = List.of(
                new PersistenceUnitDiscovery.Unit("orders", "JTA", null, null),
                new PersistenceUnitDiscovery.Unit("audit", "JTA", null, null));

        assertThrows(PersistenceException.class, () -> VidocqPersistenceUnitBootstrap.resolveUnit(units, ""));
        assertThrows(PersistenceException.class, () -> VidocqPersistenceUnitBootstrap.resolveUnit(units, "missing"));
        assertEquals("audit", VidocqPersistenceUnitBootstrap.resolveUnit(units, "audit").name());
        assertThrows(PersistenceException.class,
                () -> VidocqPersistenceUnitBootstrap.resolveUnit(List.of(), ""));
    }
}
