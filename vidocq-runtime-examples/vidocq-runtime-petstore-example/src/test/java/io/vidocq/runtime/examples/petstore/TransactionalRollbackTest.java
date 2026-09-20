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
package io.vidocq.runtime.examples.petstore;

import io.vidocq.runtime.core.VidocqBootstrap;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Extends the Vidocq/vidocq#97 coverage to this example: a {@code @Transactional} method that
 * writes a row and then throws must leave the row count unchanged.
 *
 * <p>{@code vidocq-runtime-petstore-example} shares the same dependency profile as {@code
 * vidocq-runtime-mansart-h2-example} — it depends on {@code vidocq-runtime-mansart-transactions-
 * extension} for {@code @Transactional} support, writes through Mansart Data repositories ({@link
 * PetRepository}) inside {@code @Transactional} service methods ({@link PetService}), and brings
 * no {@code mansart-transactions-jdbc} of its own. Before the fix, {@code mansart-data-cdi}'s
 * JDBC-to-JTA bridge ({@code io.vidocq.mansart.transactions.jdbc.ConnectionXAResource}) was never
 * on this application's module path either, for the same reason described on {@link
 * io.vidocq.runtime.examples.mansart.TransactionalRollbackTest}: the jar is an optional dependency
 * of {@code mansart-data-cdi} and neither Mansart extension this example depends on brought it in.
 * The fix makes {@code vidocq-runtime-mansart-transactions-extension} itself bring {@code
 * mansart-transactions-jdbc}, so this test proves the same shared fix also covers this example.
 *
 * <p>{@link PetService#createThenFailForTests()} is a package-private method added to the
 * example's existing {@code @Transactional}-writing service bean purely to give this test a unit
 * of work that writes and then fails — the example otherwise has none, and this repository's
 * Vauban-generated bean registry is produced separately per Maven compilation round (main vs.
 * test), so a CDI bean declared only in {@code src/test/java} is never merged into the main
 * round's registry and is not discoverable at runtime; hence the method lives in main sources
 * instead of a new test-scope bean. It carries no JAX-RS annotation, so Cassini never exposes it
 * as an endpoint.
 *
 * <p>Boots the application in process, purely through CDI — no HTTP call is needed to reproduce
 * the defect. Unlike {@code vidocq-runtime-mansart-h2-example}, this module has no test that
 * forces {@code vidocq.launch.mode=dev} on the whole forked JVM (it has no dev-console snapshot
 * test), so there is nothing here for a {@code test}-mode override to protect against; the launch
 * mode is left to its normal auto-detection (a JUnit Platform launcher on the class path resolves
 * it to {@code test} anyway). See {@code TransactionalRollbackTest} in {@code
 * vidocq-runtime-mansart-h2-example} for the module where that override is actually needed.
 */
class TransactionalRollbackTest {

    @Test
    void aWriteInsideAFailedTransactionIsRolledBack() {
        VidocqBootstrap bootstrap = VidocqBootstrap.create().configure().start();
        try {
            PetRepository pets = CDI.current().select(PetRepository.class).get();
            PetService service = CDI.current().select(PetService.class).get();

            long before = pets.count();
            assertThrows(IllegalStateException.class, service::createThenFailForTests,
                    "the unit of work must still throw, unwrapped, so the test proves a real "
                            + "rollback and not a swallowed failure");
            long after = pets.count();

            assertEquals(before, after,
                    "a row written just before a thrown exception must not survive the "
                            + "transaction rollback (Vidocq/vidocq#97)");
        } finally {
            bootstrap.shutdown();
        }
    }
}
