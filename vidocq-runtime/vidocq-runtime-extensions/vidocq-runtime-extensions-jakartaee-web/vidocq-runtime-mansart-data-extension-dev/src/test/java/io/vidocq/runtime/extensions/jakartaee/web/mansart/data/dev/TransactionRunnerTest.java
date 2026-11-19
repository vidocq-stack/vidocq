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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import jakarta.transaction.RollbackException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A write's transaction (spec §3), with a recording TransactionManager. */
class TransactionRunnerTest {

    private final RecordingTransactionManager manager = new RecordingTransactionManager();
    private final TransactionRunner runner = new TransactionRunner(new JtaDemarcation(() -> manager));

    @Test
    void aReadRunsWithoutATransaction() {
        TransactionRunner.Outcome<String> read = runner.run(null, () -> "read");

        assertEquals("read", read.value());
        assertNull(read.state());
        assertEquals(List.of(), manager.events);
    }

    @Test
    void rollbackComesFirstAndRollsBackAfterTheCall() {
        assertTrue(runner.available());
        assertEquals(List.of("rollback", "commit"), runner.modes());

        TransactionRunner.Outcome<Integer> outcome = runner.run("rollback", () -> {
            manager.events.add("call");
            return 1;
        });

        assertEquals(1, outcome.value());
        assertEquals("rolled back", outcome.state());
        assertEquals(List.of("begin", "call", "rollback"), manager.events);
    }

    @Test
    void commitCommits() {
        TransactionRunner.Outcome<Integer> outcome = runner.run("commit", () -> {
            manager.events.add("call");
            return 1;
        });

        assertEquals("committed", outcome.state());
        assertEquals(List.of("begin", "call", "commit"), manager.events);
    }

    @Test
    void anExceptionRollsBackWhateverWasAsked() {
        IllegalStateException boom = new IllegalStateException("boom");

        TransactionRunner.Outcome<Object> outcome = runner.run("commit", () -> {
            throw boom;
        });

        assertSame(boom, outcome.failure());
        assertNull(outcome.value());
        assertEquals("rolled back", outcome.state());
        assertEquals(List.of("begin", "rollback"), manager.events);
    }

    @Test
    void aFailedCommitIsAFailure() {
        manager.commitFailure = new RollbackException("timed out");

        TransactionRunner.Outcome<Integer> outcome = runner.run("commit", () -> 1);

        assertInstanceOf(RollbackException.class, outcome.failure());
        assertNull(outcome.state());
        assertEquals(List.of("begin", "commit"), manager.events);
    }

    @Test
    void withoutAManagerOnlyCommitAndTheCallRunsAsItIs() {
        TransactionRunner none = TransactionRunner.NONE;

        assertFalse(none.available());
        assertEquals(List.of("commit"), none.modes());
        TransactionRunner.Outcome<Integer> committed = none.run("commit", () -> 1);
        assertEquals(1, committed.value());
        assertEquals("committed", committed.state());
        assertInstanceOf(IllegalStateException.class, none.run("rollback", () -> 1).failure());
    }

    @Test
    void noBeanManagerNoTransactionManager() {
        assertFalse(TransactionRunner.of(null).available());
    }
}
