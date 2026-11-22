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

import static org.assertj.core.api.Assertions.assertThat;

import io.vidocq.noether.jpa.core.bootstrap.Definitions;
import io.vidocq.noether.jpa.core.bootstrap.UnitSettings;
import io.vidocq.noether.jpa.core.session.CountingDataSource;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceUnitTransactionType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * The data sources a unit names, which an application creating its own factory does not pass as objects
 * (Vidocq/vidocq#676): Noether looks the names up through JNDI, as written, then under {@code java:comp/env}.
 */
class NamedDataSourcesTest {

    private static final DataSource JTA = new CountingDataSource("jdbc:h2:mem:named-jta");
    private static final DataSource NON_JTA = new CountingDataSource("jdbc:h2:mem:named-non-jta");

    private static UnitSettings unit(PersistenceUnitTransactionType type, String jtaName, String nonJtaName,
            Map<String, Object> overrides) {
        PersistenceConfiguration configuration = new PersistenceConfiguration("orders").transactionType(type);
        if (jtaName != null) {
            configuration.jtaDataSource(jtaName);
        }
        if (nonJtaName != null) {
            configuration.nonJtaDataSource(nonJtaName);
        }
        return UnitSettings.of(Definitions.from(configuration, NamedDataSourcesTest.class.getClassLoader()), Map.of(),
            overrides);
    }

    /** A naming context holding {@code bound}, which records the names it is asked. */
    private static final class Naming implements Function<String, Optional<Object>> {
        final List<String> asked = new ArrayList<>();
        private final Map<String, Object> bound;

        Naming(Map<String, Object> bound) {
            this.bound = bound;
        }

        @Override
        public Optional<Object> apply(String name) {
            asked.add(name);
            return Optional.ofNullable(bound.get(name));
        }
    }

    @Test
    void aJtaUnitTakesTheDataSourceBoundUnderTheNameItGives() {
        Naming naming = new Naming(Map.of("jdbc/orders", JTA));

        UnitSettings resolved = NamedDataSources.resolve(
            unit(PersistenceUnitTransactionType.JTA, "jdbc/orders", null, Map.of()), naming);

        assertThat(resolved.property(Definitions.JTA_DATA_SOURCE)).isSameAs(JTA);
        assertThat(naming.asked).containsExactly("jdbc/orders");
    }

    @Test
    void aNameNothingAnswersAsWrittenIsLookedUpInTheEnvironmentOfTheComponent() {
        Naming naming = new Naming(Map.of("java:comp/env/jdbc/orders", NON_JTA));

        UnitSettings resolved = NamedDataSources.resolve(
            unit(PersistenceUnitTransactionType.RESOURCE_LOCAL, null, " jdbc/orders ", Map.of()), naming);

        assertThat(resolved.property(Definitions.NON_JTA_DATA_SOURCE)).isSameAs(NON_JTA);
        assertThat(naming.asked).containsExactly("jdbc/orders", "java:comp/env/jdbc/orders");
    }

    @Test
    void anAbsoluteNameIsLookedUpAsWrittenOnly() {
        Naming naming = new Naming(Map.of());
        UnitSettings unit = unit(PersistenceUnitTransactionType.RESOURCE_LOCAL, null, "java:app/jdbc/orders", Map.of());

        assertThat(NamedDataSources.resolve(unit, naming)).isSameAs(unit);
        assertThat(naming.asked).containsExactly("java:app/jdbc/orders");
    }

    @Test
    void aJtaUnitAlsoTakesTheNonJtaDataSourceItNames() {
        Naming naming = new Naming(Map.of("jdbc/orders", JTA, "jdbc/orders-raw", NON_JTA));

        UnitSettings resolved = NamedDataSources.resolve(
            unit(PersistenceUnitTransactionType.JTA, "jdbc/orders", "jdbc/orders-raw", Map.of()), naming);

        assertThat(resolved.property(Definitions.JTA_DATA_SOURCE)).isSameAs(JTA);
        assertThat(resolved.property(Definitions.NON_JTA_DATA_SOURCE)).isSameAs(NON_JTA);
    }

    @Test
    void aNameGivenAsAPropertyIsLookedUpToo() {
        Naming naming = new Naming(Map.of("jdbc/elsewhere", NON_JTA));

        UnitSettings resolved = NamedDataSources.resolve(unit(PersistenceUnitTransactionType.RESOURCE_LOCAL, null,
            "jdbc/orders", Map.of(Definitions.NON_JTA_DATA_SOURCE, "jdbc/elsewhere")), naming);

        assertThat(resolved.property(Definitions.NON_JTA_DATA_SOURCE)).isSameAs(NON_JTA);
    }

    @Test
    void aDataSourceObjectTheApplicationPassesWinsAndNothingIsLookedUp() {
        Naming naming = new Naming(Map.of("jdbc/orders", NON_JTA));
        DataSource passed = new CountingDataSource("jdbc:h2:mem:named-passed");

        UnitSettings resolved = NamedDataSources.resolve(unit(PersistenceUnitTransactionType.RESOURCE_LOCAL, null,
            "jdbc/orders", Map.of(Definitions.NON_JTA_DATA_SOURCE, passed)), naming);

        assertThat(resolved.property(Definitions.NON_JTA_DATA_SOURCE)).isSameAs(passed);
        assertThat(naming.asked).isEmpty();
    }

    @Test
    void aResourceLocalUnitGivenItsDataSourceAsAnObjectLooksNothingUp() {
        Naming naming = new Naming(Map.of("jdbc/orders", NON_JTA));
        UnitSettings unit = unit(PersistenceUnitTransactionType.RESOURCE_LOCAL, null, "jdbc/orders",
            Map.of(UnitSettings.DATA_SOURCE, new CountingDataSource("jdbc:h2:mem:named-passed")));

        assertThat(NamedDataSources.resolve(unit, naming)).isSameAs(unit);
        assertThat(naming.asked).isEmpty();
    }

    @Test
    void aUnitThatNamesNoDataSourceLooksNothingUp() {
        Naming naming = new Naming(Map.of());
        UnitSettings unit = unit(PersistenceUnitTransactionType.RESOURCE_LOCAL, null, null, Map.of());

        assertThat(NamedDataSources.resolve(unit, naming)).isSameAs(unit);
        assertThat(naming.asked).isEmpty();
    }

    @Test
    void aNameBoundToSomethingElseThanADataSourceLeavesTheUnitUnchanged() {
        UnitSettings unit = unit(PersistenceUnitTransactionType.JTA, "jdbc/orders", null, Map.of());

        assertThat(NamedDataSources.resolve(unit, new Naming(Map.of("jdbc/orders", "not a data source")))).isSameAs(unit);
    }

    @Test
    void aNameNothingAnswersIsLeftForTheRefusalOfTheUnit() {
        UnitSettings unit = unit(PersistenceUnitTransactionType.JTA, "jdbc/orders", null, Map.of());

        assertThat(NamedDataSources.resolve(unit, new Naming(Map.of()))).isSameAs(unit);
    }

    @Test
    void withoutANamingContextTheUnitIsUnchanged() {
        UnitSettings unit = unit(PersistenceUnitTransactionType.JTA, "jdbc/orders", null, Map.of());

        assertThat(NamedDataSources.resolve(unit)).isSameAs(unit);
    }
}
