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
package io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.units;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.noether.jpa.core.spi.PersistenceUnitDeclaration;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PersistenceUnitTransactionType;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The persistence units the {@code vidocq.persistence.*} keys declare (Vidocq/vidocq#551). */
class UnitKeysTest {

    private static final URL APPLICATION = url("file:/app/classes/");
    private static final URL LEDGER = url("file:/app/ledger.jar");

    /** An application in module {@code acme.app}, beside a module {@code acme.ledger}. */
    private static final ModuleRoots MODULES = new ModuleRoots() {
        @Override
        public Optional<URL> application() {
            return Optional.of(APPLICATION);
        }

        @Override
        public Optional<URL> of(String name) {
            return switch (name) {
                case "acme.app" -> Optional.of(APPLICATION);
                case "acme.ledger" -> Optional.of(LEDGER);
                default -> Optional.empty();
            };
        }
    };

    @Test
    void aUnitTheKeysDeclareManagesTheEntitiesOfItsModuleInJtaTransactions() {
        PersistenceUnitDeclaration ledger = single(Map.of("vidocq.persistence.ledger.module", "acme.ledger"));

        assertEquals("ledger", ledger.name());
        assertEquals("the vidocq.persistence.ledger.* keys", ledger.origin());
        assertTrue(ledger.declares());
        assertEquals(Optional.of(LEDGER), ledger.root());
        assertEquals(Optional.of(PersistenceUnitTransactionType.JTA), ledger.defaultTransactionType());
        assertFalse(ledger.whenAlone());
    }

    @Test
    void eachKeySetsItsAttribute() {
        PersistenceUnitDeclaration ledger = single(Map.of(
                "vidocq.persistence.ledger.packages", "com.acme.ledger, com.acme.shared",
                "vidocq.persistence.ledger.classes", "com.acme.Account,com.acme.Entry",
                "vidocq.persistence.ledger.transaction-type", "RESOURCE_LOCAL",
                "vidocq.persistence.ledger.qualifier", "com.acme.Ledger",
                "vidocq.persistence.ledger.scope", "jakarta.enterprise.context.RequestScoped",
                "vidocq.persistence.ledger.datasource", "accounts",
                "vidocq.persistence.ledger.non-jta-datasource", "sequences",
                "vidocq.persistence.ledger.properties.jakarta.persistence.schema-generation.database.action",
                "create"));

        assertEquals(List.of("com.acme.ledger", "com.acme.shared"), ledger.packages());
        assertEquals(List.of("com.acme.Account", "com.acme.Entry"), ledger.classes());
        assertEquals(Optional.of(PersistenceUnitTransactionType.RESOURCE_LOCAL), ledger.transactionType());
        assertEquals(List.of("com.acme.Ledger"), ledger.qualifiers());
        assertEquals(Optional.of("jakarta.enterprise.context.RequestScoped"), ledger.scope());
        assertEquals(Optional.of("accounts"), ledger.dataSource());
        assertEquals(Optional.of("sequences"), ledger.nonJtaDataSource());
        assertEquals(Map.of("jakarta.persistence.schema-generation.database.action", "create"), ledger.properties());
        assertEquals(Optional.of(APPLICATION), ledger.defaultRoot(), "without a module key, the application's");
    }

    /** Decision 1: the data sources and the properties alone override a unit a persistence.xml declares. */
    @Test
    void theDataSourcesAndThePropertiesAloneOnlyOverride() {
        PersistenceUnitDeclaration ledger = single(Map.of(
                "vidocq.persistence.ledger.datasource", "production",
                "vidocq.persistence.ledger.properties.noether.show-sql", "true"));

        assertFalse(ledger.declares());
        assertEquals(Optional.of("production"), ledger.dataSource());
        assertEquals(Optional.of(APPLICATION), ledger.defaultRoot());
        assertEquals(Optional.of(PersistenceUnitTransactionType.JTA), ledger.defaultTransactionType());
    }

    @Test
    void aQuotedUnitNameMayHoldDots() {
        PersistenceUnitDeclaration unit = single(Map.of("vidocq.persistence.\"com.acme.ledger\".datasource", "x"));

        assertEquals("com.acme.ledger", unit.name());
        assertEquals("the vidocq.persistence.\"com.acme.ledger\".* keys", unit.origin());
    }

    @Test
    void theUnitsComeInTheOrderOfTheirNames() {
        List<PersistenceUnitDeclaration> units = UnitKeys.declarations(Map.of(
                "vidocq.persistence.ledger.datasource", "a",
                "vidocq.persistence.audit.datasource", "b",
                "vidocq.persistence.ledger.properties.p", "v"), MODULES);

        assertEquals(List.of("audit", "ledger"), units.stream().map(PersistenceUnitDeclaration::name).toList());
    }

    /** Decision 2: no key, then the default unit, which Noether declares when no persistence.xml declares one. */
    @Test
    void withoutAKeyTheDefaultUnitTakesTheApplicationModuleWhenAlone() {
        PersistenceUnitDeclaration unit = single(Map.of("vidocq.pool.url", "jdbc:h2:mem:a"));

        assertEquals(UnitKeys.DEFAULT_UNIT, unit.name());
        assertTrue(unit.whenAlone());
        assertEquals(Optional.of(APPLICATION), unit.defaultRoot());
        assertEquals(Optional.of(PersistenceUnitTransactionType.JTA), unit.defaultTransactionType());
    }

    @Test
    void anApplicationThatIsNoNamedModuleHasNoDefaultUnit() {
        ModuleRoots unnamed = new ModuleRoots() {
            @Override
            public Optional<URL> application() {
                return Optional.empty();
            }

            @Override
            public Optional<URL> of(String name) {
                return Optional.empty();
            }
        };

        assertEquals(List.of(), UnitKeys.declarations(Map.of(), unnamed));
    }

    @Test
    void anUnknownKeyNamesTheKeysThereAre() {
        Map<String, String> keys = Map.of("vidocq.persistence.ledger.data-source", "x");

        PersistenceException failure = assertThrows(PersistenceException.class,
                () -> UnitKeys.declarations(keys, MODULES));

        assertTrue(failure.getMessage().contains("vidocq.persistence.ledger.data-source"), failure.getMessage());
        assertTrue(failure.getMessage().contains("datasource, non-jta-datasource"), failure.getMessage());
        assertTrue(failure.getMessage().contains("properties.<name>"), failure.getMessage());
    }

    @Test
    void aKeyWithoutAnAttributeIsRefused() {
        Map<String, String> keys = Map.of("vidocq.persistence.ledger", "x");

        PersistenceException failure = assertThrows(PersistenceException.class,
                () -> UnitKeys.declarations(keys, MODULES));

        assertTrue(failure.getMessage().contains("vidocq.persistence.ledger"), failure.getMessage());
    }

    @Test
    void aModuleTheApplicationDoesNotHaveIsRefused() {
        Map<String, String> keys = Map.of("vidocq.persistence.ledger.module", "acme.absent");

        PersistenceException failure = assertThrows(PersistenceException.class,
                () -> UnitKeys.declarations(keys, MODULES));

        assertTrue(failure.getMessage().contains("vidocq.persistence.ledger.module"), failure.getMessage());
        assertTrue(failure.getMessage().contains("acme.absent"), failure.getMessage());
    }

    @Test
    void anUnknownTransactionTypeIsRefused() {
        Map<String, String> keys = Map.of("vidocq.persistence.ledger.transaction-type", "XA");

        PersistenceException failure = assertThrows(PersistenceException.class,
                () -> UnitKeys.declarations(keys, MODULES));

        assertTrue(failure.getMessage().contains("JTA or RESOURCE_LOCAL"), failure.getMessage());
    }

    @Test
    void anUnclosedQuoteIsRefused() {
        Map<String, String> keys = Map.of("vidocq.persistence.\"com.acme.datasource", "x");

        PersistenceException failure = assertThrows(PersistenceException.class,
                () -> UnitKeys.declarations(keys, MODULES));

        assertTrue(failure.getMessage().contains("quote"), failure.getMessage());
    }

    private static PersistenceUnitDeclaration single(Map<String, String> keys) {
        List<PersistenceUnitDeclaration> units = UnitKeys.declarations(keys, MODULES);
        assertEquals(1, units.size(), units::toString);
        return units.getFirst();
    }

    private static URL url(String spec) {
        try {
            return URI.create(spec).toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
