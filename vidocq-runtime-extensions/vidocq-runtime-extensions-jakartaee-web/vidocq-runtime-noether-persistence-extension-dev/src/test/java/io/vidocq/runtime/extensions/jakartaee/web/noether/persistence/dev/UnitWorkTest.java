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
package io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev;

import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev.UnitWork.Mode;
import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev.UnitWork.Outcome;
import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.live.NoetherPersistenceLive.Unit;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import jakarta.transaction.RollbackException;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The work of an action on a unit: one entity manager and one transaction, both ended whatever happens. */
class UnitWorkTest {

    private final List<String> calls = new ArrayList<>();

    private Unit local() {
        boolean[] active = {false};
        EntityTransaction transaction = Stubs.of(EntityTransaction.class, calls, Map.of(
                "begin", args -> {
                    active[0] = true;
                    return null;
                },
                "commit", args -> {
                    active[0] = false;
                    return null;
                },
                "rollback", args -> {
                    active[0] = false;
                    return null;
                },
                "isActive", args -> active[0]));
        EntityManager em = Stubs.of(EntityManager.class, calls, Map.of(
                "getTransaction", args -> transaction,
                "createQuery", args -> {
                    throw new IllegalArgumentException("Unexpected token SELEC");
                },
                "close", Stubs.NOTHING));
        return new Unit("notes", false, null, "none", new StubFactory("notes", null, null, () -> em));
    }

    private Unit jta() {
        EntityManager em = Stubs.of(EntityManager.class, calls, Map.of("joinTransaction", Stubs.NOTHING,
                "close", Stubs.NOTHING));
        return new Unit("notes", true, null, "none", new StubFactory("notes", null, null, () -> em));
    }

    private TransactionManager manager(boolean commitFails) {
        int[] status = {Status.STATUS_NO_TRANSACTION};
        return Stubs.of(TransactionManager.class, calls, Map.of(
                "begin", args -> {
                    status[0] = Status.STATUS_ACTIVE;
                    return null;
                },
                "commit", args -> {
                    status[0] = Status.STATUS_NO_TRANSACTION;
                    if (commitFails) {
                        sneaky(new RollbackException("marked for rollback"));
                    }
                    return null;
                },
                "rollback", args -> {
                    status[0] = Status.STATUS_NO_TRANSACTION;
                    return null;
                },
                "getStatus", args -> status[0]));
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneaky(Throwable failure) throws E {
        throw (E) failure;
    }

    @Test
    void aBadQueryAnswersAnErrorAndLeavesNothingOpen() {
        Outcome<Object> outcome = new UnitWork(() -> null).run(local(), Mode.READ, em -> em.createQuery("SELEC"));

        assertInstanceOf(IllegalArgumentException.class, outcome.failure());
        assertEquals(List.of("getTransaction", "begin", "createQuery", "isActive", "rollback", "close"), calls);
    }

    @Test
    void aReadIsRolledBackAndACommitCommits() {
        Outcome<Integer> read = new UnitWork(() -> null).run(local(), Mode.READ, em -> 1);
        assertEquals(1, read.value());
        assertEquals("rolled back", read.state());
        assertFalse(calls.contains("commit"));

        calls.clear();
        Outcome<Integer> written = new UnitWork(() -> null).run(local(), Mode.COMMIT, em -> 2);
        assertEquals("committed", written.state());
        assertEquals(List.of("getTransaction", "begin", "commit", "close"), calls);
    }

    @Test
    void aJtaUnitWorksInTheApplicationsTransaction() {
        TransactionManager manager = manager(false);

        Outcome<Integer> written = new UnitWork(() -> manager).run(jta(), Mode.COMMIT, em -> 3);

        assertEquals(3, written.value());
        assertEquals("committed", written.state());
        assertEquals(List.of("begin", "joinTransaction", "commit", "close"), calls);
        calls.clear();
        assertEquals("rolled back", new UnitWork(() -> manager).run(jta(), Mode.ROLLBACK, em -> 3).state());
        assertEquals(List.of("begin", "joinTransaction", "rollback", "close"), calls);
    }

    @Test
    void aFailedCommitIsAFailureAndTheEntityManagerIsClosed() {
        TransactionManager manager = manager(true);

        Outcome<Integer> written = new UnitWork(() -> manager).run(jta(), Mode.COMMIT, em -> 3);

        assertNull(written.value());
        assertInstanceOf(RollbackException.class, written.failure().getCause());
        assertEquals("close", calls.getLast());
    }

    @Test
    void aJtaUnitWithoutATransactionManagerSaysSo() {
        Outcome<Integer> outcome = new UnitWork(() -> null).run(jta(), Mode.READ, em -> 1);

        assertEquals("no TransactionManager bean: the work of a JTA unit runs in the application's transactions",
                outcome.failure().getMessage());
        assertEquals(List.of(), calls);
    }
}
