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

import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.live.NoetherPersistenceLive.Unit;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import jakarta.transaction.HeuristicMixedException;
import jakarta.transaction.HeuristicRollbackException;
import jakarta.transaction.NotSupportedException;
import jakarta.transaction.RollbackException;
import jakarta.transaction.Status;
import jakarta.transaction.SystemException;
import jakarta.transaction.TransactionManager;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The work of one action on a unit: one entity manager and one transaction, ended whatever happens. A RESOURCE_LOCAL
 * unit runs in its own {@link EntityTransaction}; a JTA unit in a transaction of the application's
 * {@link TransactionManager}, which its entity manager joins. A read is always rolled back.
 */
final class UnitWork {

    /** How the transaction ends. */
    enum Mode {
        /** A read: rolled back, whatever the statement did. */
        READ,
        /** A write that is rolled back. */
        ROLLBACK,
        /** A write that is committed. */
        COMMIT
    }

    /**
     * What the work gave.
     *
     * @param value   its value, {@code null} when it failed
     * @param failure what failed, {@code null} when it did not
     * @param state   {@code committed} or {@code rolled back}, {@code null} when it failed
     */
    record Outcome<T>(T value, RuntimeException failure, String state) {}

    private static final System.Logger LOG = System.getLogger(UnitWork.class.getName());

    private final Supplier<TransactionManager> manager;

    /** @param manager the application's transaction manager, resolved at each call; {@code null} when it has none */
    UnitWork(Supplier<TransactionManager> manager) {
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    /** Runs {@code work} on an entity manager of {@code unit}, in a transaction that ends as {@code mode} says. */
    <T> Outcome<T> run(Unit unit, Mode mode, Function<EntityManager, T> work) {
        Transaction transaction = null;
        EntityManager em = null;
        try {
            if (unit.jta()) {
                TransactionManager jta = manager.get();
                if (jta == null) {
                    throw new IllegalStateException("no TransactionManager bean: the work of a JTA unit runs in the"
                            + " application's transactions");
                }
                transaction = begin(jta);
                em = unit.factory().createEntityManager();
                em.joinTransaction();
            } else {
                em = unit.factory().createEntityManager();
                transaction = begin(em.getTransaction());
            }
            T value = work.apply(em);
            boolean commit = mode == Mode.COMMIT;
            Transaction ending = transaction;
            transaction = null;
            ending.end(commit);
            return new Outcome<>(value, null, commit ? "committed" : "rolled back");
        } catch (RuntimeException failed) {
            abort(transaction);
            return new Outcome<>(null, failed, null);
        } finally {
            close(em);
        }
    }

    /** A transaction the work runs in. */
    private interface Transaction {

        /** Commits it, or rolls it back. */
        void end(boolean commit);

        /** Rolls it back when it is still active; never throws. */
        void abort();
    }

    private static Transaction begin(EntityTransaction local) {
        local.begin();
        return new Transaction() {
            @Override
            public void end(boolean commit) {
                if (commit) {
                    local.commit();
                } else {
                    local.rollback();
                }
            }

            @Override
            public void abort() {
                if (local.isActive()) {
                    local.rollback();
                }
            }
        };
    }

    private static Transaction begin(TransactionManager jta) {
        try {
            jta.begin();
        } catch (NotSupportedException | SystemException failed) {
            throw new IllegalStateException("the transaction could not begin: " + failed.getMessage(), failed);
        }
        return new Transaction() {
            @Override
            public void end(boolean commit) {
                try {
                    if (commit) {
                        jta.commit();
                    } else {
                        jta.rollback();
                    }
                } catch (RollbackException | HeuristicMixedException | HeuristicRollbackException
                         | SystemException failed) {
                    throw new IllegalStateException("the transaction did not " + (commit ? "commit" : "roll back")
                            + ": " + failed.getMessage(), failed);
                }
            }

            @Override
            public void abort() {
                try {
                    if (jta.getStatus() != Status.STATUS_NO_TRANSACTION) {
                        jta.rollback();
                    }
                } catch (SystemException failed) {
                    throw new IllegalStateException(failed);
                }
            }
        };
    }

    private static void abort(Transaction transaction) {
        if (transaction == null) {
            return;
        }
        try {
            transaction.abort();
        } catch (RuntimeException failed) {
            LOG.log(System.Logger.Level.DEBUG, "the rollback after a failure failed too", failed);
        }
    }

    private static void close(EntityManager em) {
        if (em == null) {
            return;
        }
        try {
            em.close();
        } catch (RuntimeException failed) {
            LOG.log(System.Logger.Level.DEBUG, "the entity manager did not close", failed);
        }
    }
}
