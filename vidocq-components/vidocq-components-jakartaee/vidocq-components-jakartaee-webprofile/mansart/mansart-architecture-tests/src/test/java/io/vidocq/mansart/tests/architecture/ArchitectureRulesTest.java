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
package io.vidocq.mansart.tests.architecture;

import static io.vidocq.mansart.tests.architecture.MansartClasses.SHIPPED;
import static io.vidocq.mansart.tests.architecture.MansartClasses.SHIPPED_WITHOUT_THREAD_LOCAL_HOLDERS;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Named.named;

import java.util.stream.Stream;

import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The architecture rules on the classes Mansart ships: mansart-pool-api and -core, the four mansart-transactions
 * modules, and the mansart-data dialect SPI, dialects, core, processor and cdi, test classes left out
 * (Vidocq/vidocq#289). A failure names each class and the dependency or call that breaks the rule.
 */
class ArchitectureRulesTest {

    @Test
    void theShippedClassesAreFound() {
        // Guards the other tests: a class path change that imports nothing would make every rule pass.
        assertTrue(SHIPPED.contain("io.vidocq.mansart.pool.PoolConfig"), "mansart-pool-api imported");
        assertTrue(SHIPPED.contain("io.vidocq.mansart.pool.core.MansartDataSource"), "mansart-pool-core imported");
        assertTrue(
                SHIPPED.contain("io.vidocq.mansart.transactions.api.MansartTransactions"),
                "mansart-transactions-api imported");
        assertTrue(
                SHIPPED.contain("io.vidocq.mansart.transactions.core.MansartTransactionManager"),
                "mansart-transactions-core imported");
        assertTrue(
                SHIPPED.contain("io.vidocq.mansart.transactions.jdbc.ConnectionXAResource"),
                "mansart-transactions-jdbc imported");
        assertTrue(
                SHIPPED.contain("io.vidocq.mansart.transactions.cdi.MansartTransactionsProducer"),
                "mansart-transactions-cdi imported");
        assertTrue(SHIPPED.contain("io.vidocq.mansart.data.dialect.Dialect"), "mansart-data-dialect-spi imported");
        assertTrue(SHIPPED.contain("io.vidocq.mansart.data.dialect.h2.H2Dialect"), "mansart-data-dialect-h2 imported");
        assertTrue(
                SHIPPED.contain("io.vidocq.mansart.data.dialect.postgresql.PostgresqlDialectFactory"),
                "mansart-data-dialect-postgresql imported");
        assertTrue(SHIPPED.contain("io.vidocq.mansart.data.core.MansartData"), "mansart-data-core imported");
        assertTrue(
                SHIPPED.contain("io.vidocq.mansart.data.processor.MansartProcessor"),
                "mansart-data-processor imported");
        assertTrue(SHIPPED.contain("io.vidocq.mansart.data.cdi.MansartDataExtension"), "mansart-data-cdi imported");
    }

    static Stream<Named<ArchRule>> rules() {
        return Stream.of(
                named("POOL_API_DEPENDENCIES", ArchitectureRules.POOL_API_DEPENDENCIES),
                named("POOL_CORE_DEPENDENCIES", ArchitectureRules.POOL_CORE_DEPENDENCIES),
                named("TRANSACTIONS_API_DEPENDENCIES", ArchitectureRules.TRANSACTIONS_API_DEPENDENCIES),
                named("TRANSACTIONS_CORE_DEPENDENCIES", ArchitectureRules.TRANSACTIONS_CORE_DEPENDENCIES),
                named("TRANSACTIONS_JDBC_DEPENDENCIES", ArchitectureRules.TRANSACTIONS_JDBC_DEPENDENCIES),
                named("TRANSACTIONS_CDI_DEPENDENCIES", ArchitectureRules.TRANSACTIONS_CDI_DEPENDENCIES),
                named("DIALECT_SPI_DEPENDENCIES", ArchitectureRules.DIALECT_SPI_DEPENDENCIES),
                named("DIALECT_DEPENDENCIES", ArchitectureRules.DIALECT_DEPENDENCIES),
                named("DATA_CORE_DEPENDENCIES", ArchitectureRules.DATA_CORE_DEPENDENCIES),
                named("DATA_PROCESSOR_DEPENDENCIES", ArchitectureRules.DATA_PROCESSOR_DEPENDENCIES),
                named("DATA_CDI_DEPENDENCIES", ArchitectureRules.DATA_CDI_DEPENDENCIES),
                named("LAYERS", ArchitectureRules.LAYERS),
                named("API_NEVER_ON_CORE", ArchitectureRules.API_NEVER_ON_CORE),
                named("POOL_STANDS_ALONE", ArchitectureRules.POOL_STANDS_ALONE),
                named("FOUNDATIONAL_BRICKS", ArchitectureRules.FOUNDATIONAL_BRICKS),
                named("NO_REFLECTION", ArchitectureRules.NO_REFLECTION),
                named("NO_PLATFORM_THREADS", ArchitectureRules.NO_PLATFORM_THREADS));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    void theShippedClassesKeepTheRule(ArchRule rule) {
        rule.check(SHIPPED);
    }

    @Test
    void theShippedClassesKeepTheThreadLocalRuleOutsideTheDocumentedHolders() {
        // The transaction context is the one documented ThreadLocal (ArchitectureRules.THREAD_LOCAL_HOLDERS).
        ArchitectureRules.NO_THREAD_LOCAL.check(SHIPPED_WITHOUT_THREAD_LOCAL_HOLDERS);
    }
}
