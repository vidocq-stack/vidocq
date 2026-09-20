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
package io.vidocq.runtime.examples.mansart;

import io.vidocq.runtime.core.VidocqBootstrap;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Reproduces Vidocq/vidocq#97: a {@code @Transactional} method that writes a row and then throws
 * must leave the row count unchanged.
 *
 * <p>Before the fix, {@code mansart-data-cdi}'s JDBC-to-JTA bridge
 * ({@code io.vidocq.mansart.transactions.jdbc.ConnectionXAResource}, shipped in
 * {@code mansart-transactions-jdbc}) was never on this application's module path — the jar is an
 * optional dependency of {@code mansart-data-cdi} and neither Mansart extension this example
 * depends on brought it in. Every repository call then took its own autocommit connection: the
 * {@code @Transactional} interceptor still began and rolled back a JTA transaction, but no JDBC
 * connection was ever enlisted in it, so the write from
 * {@code ProductResource.saveThenFailForTests()} survived (row count 3 -&gt; 4). The fix makes
 * {@code vidocq-runtime-mansart-transactions-extension} bring {@code mansart-transactions-jdbc}
 * itself, so this test boots the real application exactly as the documentation recommends it and
 * proves the row count is unchanged.
 *
 * <p>{@code ProductResource.saveThenFailForTests()} is a package-private method added to the
 * example's existing {@code @Transactional}-writing resource bean purely to give this test a unit
 * of work that writes and then fails — the example otherwise has none, and this repository's
 * Vauban-generated bean registry is produced separately per Maven compilation round (main vs.
 * test), so a CDI bean declared only in {@code src/test/java} is never merged into the main
 * round's registry and is not discoverable at runtime; hence the method lives in main sources
 * instead of a new test-scope bean. It carries no JAX-RS annotation, so Cassini never exposes it
 * as an endpoint.
 *
 * <p>Boots the application in process, purely through CDI — no HTTP call is needed to reproduce
 * the defect. This module's surefire plugin forces {@code vidocq.launch.mode=dev} (for
 * {@link DevConsoleSnapshotTest}) on the whole forked JVM: this test overrides it to {@code test}
 * for the duration of its own boot, and restores it afterwards. Vidocq reads
 * {@code vidocq.launch.mode} fresh on every {@code configure()} (see
 * {@code StartupReportActivationTest}), so the override is safe; running as {@code dev} would
 * leave the dev console pointed at the port the earlier boot already bound in this JVM — the
 * console only prints its URL again when the address changes (see {@code ConsoleMemory}) — which
 * would starve {@link DevConsoleSnapshotTest} of the log record it depends on when both tests run
 * in the same forked JVM, as they do by default (one fork, reused across this module's classes).
 * "The container, the configuration and the extensions' lifecycle are the same in every mode"
 * ({@code LaunchMode}), so the launch mode itself has no bearing on the write-then-rollback this
 * test proves.
 */
class TransactionalRollbackTest {

    private static final String LAUNCH_MODE_KEY = "vidocq.launch.mode";

    @Test
    void aWriteInsideAFailedTransactionIsRolledBack() {
        String previousLaunchMode = System.getProperty(LAUNCH_MODE_KEY);
        System.setProperty(LAUNCH_MODE_KEY, "test");
        VidocqBootstrap bootstrap;
        try {
            bootstrap = VidocqBootstrap.create().configure().start();
        } finally {
            restore(previousLaunchMode);
        }
        try {
            ProductRepository products = CDI.current().select(ProductRepository.class).get();
            ProductResource resource = CDI.current().select(ProductResource.class).get();

            long before = products.count();
            assertThrows(IllegalStateException.class, resource::saveThenFailForTests,
                    "the unit of work must still throw, unwrapped, so the test proves a real "
                            + "rollback and not a swallowed failure");
            long after = products.count();

            assertEquals(before, after,
                    "a row written just before a thrown exception must not survive the "
                            + "transaction rollback (Vidocq/vidocq#97)");
        } finally {
            bootstrap.shutdown();
        }
    }

    private static void restore(String previousLaunchMode) {
        if (previousLaunchMode == null) {
            System.clearProperty(LAUNCH_MODE_KEY);
        } else {
            System.setProperty(LAUNCH_MODE_KEY, previousLaunchMode);
        }
    }
}
