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

import jakarta.enterprise.inject.spi.BeanManager;

import java.util.List;
import java.util.Objects;

/**
 * Runs a call as spec §3 says: a read as it is; a write in a transaction of the application's
 * {@code jakarta.transaction.TransactionManager}, begun, then rolled back or committed as asked, and always rolled back
 * when the call throws. Without a manager — no bean, or not even the API on the module path — a write can only be
 * committed, and runs as it is (Mansart then autocommits).
 *
 * <p>This class never names a {@code jakarta.transaction} type: {@link JtaDemarcation} does, and is only loaded by
 * {@link #of}, which falls back to {@link #NONE} when it cannot be.
 */
final class TransactionRunner {

    static final String ROLLBACK = "rollback";
    static final String COMMIT = "commit";
    static final String COMMITTED = "committed";
    static final String ROLLED_BACK = "rolled back";

    /** No transaction manager: a write is committed as it runs. */
    static final TransactionRunner NONE = new TransactionRunner(null);

    /** Begins, commits and rolls back a transaction of the application. */
    interface Demarcation {
        void begin() throws Exception;

        void commit() throws Exception;

        void rollback() throws Exception;
    }

    /** The call, and whatever reads its result, run inside the transaction. */
    @FunctionalInterface
    interface Work<T> {
        T run() throws Throwable;
    }

    /**
     * How a call ended.
     *
     * @param value   what the work returned, {@code null} when it failed
     * @param failure what the work or the transaction threw, {@code null} when it went through
     * @param state   {@value #COMMITTED}, {@value #ROLLED_BACK}, or {@code null} for a read or when unknown
     */
    record Outcome<T>(T value, Throwable failure, String state) {}

    private final Demarcation demarcation;

    TransactionRunner(Demarcation demarcation) {
        this.demarcation = demarcation;
    }

    /** The runner of the application behind {@code beans}; {@link #NONE} when it has no transaction manager. */
    static TransactionRunner of(BeanManager beans) {
        try {
            Demarcation found = JtaDemarcation.of(beans);
            return found == null ? NONE : new TransactionRunner(found);
        } catch (RuntimeException | LinkageError absent) {
            return NONE;
        }
    }

    /** Whether writes run in a transaction that can be rolled back. */
    boolean available() {
        return demarcation != null;
    }

    /** The values of a write's {@code transaction} argument, the default first. */
    List<String> modes() {
        return available() ? List.of(ROLLBACK, COMMIT) : List.of(COMMIT);
    }

    /**
     * Runs {@code work}.
     *
     * @param mode {@code null} for a read, else {@value #ROLLBACK} or {@value #COMMIT}
     */
    <T> Outcome<T> run(String mode, Work<T> work) {
        Objects.requireNonNull(work, "work");
        if (mode == null) {
            return call(work, null);
        }
        if (demarcation == null) {
            return COMMIT.equals(mode) ? call(work, COMMITTED) : new Outcome<>(null,
                    new IllegalStateException("no TransactionManager: a write can only be committed"), null);
        }
        if (!COMMIT.equals(mode) && !ROLLBACK.equals(mode)) {
            return new Outcome<>(null, new IllegalArgumentException("no transaction mode " + mode), null);
        }
        try {
            demarcation.begin();
        } catch (Exception failed) {
            return new Outcome<>(null, failed, null);
        }
        T value;
        try {
            value = work.run();
        } catch (Throwable failure) {
            rollback(failure);
            if (failure instanceof VirtualMachineError fatal) {
                throw fatal;
            }
            return new Outcome<>(null, failure, ROLLED_BACK);
        }
        try {
            if (COMMIT.equals(mode)) {
                demarcation.commit();
                return new Outcome<>(value, null, COMMITTED);
            }
            demarcation.rollback();
            return new Outcome<>(value, null, ROLLED_BACK);
        } catch (Exception failed) {
            return new Outcome<>(null, failed, null);
        }
    }

    private static <T> Outcome<T> call(Work<T> work, String state) {
        try {
            return new Outcome<>(work.run(), null, state);
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) {
                throw fatal;
            }
            return new Outcome<>(null, failure, null);
        }
    }

    private void rollback(Throwable failure) {
        try {
            demarcation.rollback();
        } catch (Exception suppressed) {
            failure.addSuppressed(suppressed);
        }
    }
}
