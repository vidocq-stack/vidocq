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
package io.vidocq.mansart.transactions.cdi;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import jakarta.transaction.InvalidTransactionException;
import jakarta.transaction.Status;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionRequiredException;
import jakarta.transaction.Transactional;
import jakarta.transaction.TransactionalException;

import java.lang.reflect.Method;

/**
 * Interceptor implementing {@link Transactional} for the six TxType values defined by
 * Jakarta Transactions 2.0 §3.7 :
 *
 * <ul>
 *   <li><b>REQUIRED</b>      — join the active TX, otherwise begin one (default).</li>
 *   <li><b>REQUIRES_NEW</b>  — suspend any active TX, begin a new one, resume the original after.</li>
 *   <li><b>MANDATORY</b>     — fail with {@link TransactionalException} if no active TX.</li>
 *   <li><b>NEVER</b>         — fail with {@link TransactionalException} if an active TX exists.</li>
 *   <li><b>NOT_SUPPORTED</b> — suspend any active TX, run without one, resume after.</li>
 *   <li><b>SUPPORTS</b>      — run inside the active TX if any, otherwise without.</li>
 * </ul>
 *
 * <p>Rollback rules (§3.7.1) — an exception {@code E} from the wrapped invocation triggers
 * rollback iff :
 * <ol>
 *   <li>no class in {@code dontRollbackOn} is assignable from {@code E}, AND</li>
 *   <li>either some class in {@code rollbackOn} is assignable from {@code E},
 *       OR {@code E} is a {@link RuntimeException} or {@link Error}.</li>
 * </ol>
 *
 * <p>Priority sits at {@link Interceptor.Priority#PLATFORM_BEFORE} + 200 so it runs before
 * application interceptors but does not collide with the platform reservation range.
 */
@Interceptor
@Transactional
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 200)
public class TransactionalInterceptor {

    @Inject
    TransactionManager tm;

    @AroundInvoke
    public Object invoke(InvocationContext ic) throws Exception {
        Transactional ann = resolveAnnotation(ic);
        Transactional.TxType type = ann.value();

        return switch (type) {
            case REQUIRED      -> required(ic, ann);
            case REQUIRES_NEW  -> requiresNew(ic, ann);
            case MANDATORY     -> mandatory(ic, ann);
            case NEVER         -> never(ic);
            case NOT_SUPPORTED -> notSupported(ic);
            case SUPPORTS      -> supports(ic, ann);
        };
    }

    private Object required(InvocationContext ic, Transactional ann) throws Exception {
        if (isActive()) {
            return executeJoining(ic, ann);
        }
        return executeWithOwnTx(ic, ann);
    }

    private Object requiresNew(InvocationContext ic, Transactional ann) throws Exception {
        Transaction suspended = isActive() ? tm.suspend() : null;
        try {
            return executeWithOwnTx(ic, ann);
        } finally {
            if (suspended != null) tm.resume(suspended);
        }
    }

    private Object mandatory(InvocationContext ic, Transactional ann) throws Exception {
        if (!isActive()) {
            throw new TransactionalException("@Transactional(MANDATORY) requires an active TX",
                    new TransactionRequiredException());
        }
        return executeJoining(ic, ann);
    }

    private Object never(InvocationContext ic) throws Exception {
        if (isActive()) {
            throw new TransactionalException("@Transactional(NEVER) forbids an active TX",
                    new InvalidTransactionException());
        }
        return ic.proceed();
    }

    private Object notSupported(InvocationContext ic) throws Exception {
        Transaction suspended = isActive() ? tm.suspend() : null;
        try {
            return ic.proceed();
        } finally {
            if (suspended != null) tm.resume(suspended);
        }
    }

    private Object supports(InvocationContext ic, Transactional ann) throws Exception {
        if (isActive()) {
            return executeJoining(ic, ann);
        }
        return ic.proceed();
    }

    /** Joins an existing TX — never commits or rolls back, just sets rollback-only on bad
     *  exceptions so the outer caller's commit observes the marking. */
    private Object executeJoining(InvocationContext ic, Transactional ann) throws Exception {
        try {
            return ic.proceed();
        } catch (Exception e) {
            if (shouldRollback(e, ann)) {
                tm.setRollbackOnly();
            }
            throw e;
        }
    }

    /** Owns the TX from begin to commit/rollback. */
    private Object executeWithOwnTx(InvocationContext ic, Transactional ann) throws Exception {
        tm.begin();
        try {
            Object result = ic.proceed();
            if (tm.getStatus() == Status.STATUS_MARKED_ROLLBACK) {
                tm.rollback();
            } else {
                tm.commit();
            }
            return result;
        } catch (Exception e) {
            if (shouldRollback(e, ann)) {
                safeRollback();
            } else {
                // Exception that's NOT a rollback trigger — commit and rethrow.
                if (tm.getStatus() == Status.STATUS_MARKED_ROLLBACK) {
                    safeRollback();
                } else {
                    try { tm.commit(); } catch (Exception commitEx) { e.addSuppressed(commitEx); }
                }
            }
            throw e;
        }
    }

    private void safeRollback() {
        try { tm.rollback(); } catch (Exception ignored) { /* best effort */ }
    }

    private boolean isActive() throws Exception {
        return tm.getStatus() == Status.STATUS_ACTIVE
            || tm.getStatus() == Status.STATUS_MARKED_ROLLBACK;
    }

    static boolean shouldRollback(Throwable e, Transactional ann) {
        // Spec §3.7.1 — dontRollbackOn wins over rollbackOn AND the default.
        for (Class<?> c : ann.dontRollbackOn()) {
            if (c.isInstance(e)) return false;
        }
        for (Class<?> c : ann.rollbackOn()) {
            if (c.isInstance(e)) return true;
        }
        return e instanceof RuntimeException || e instanceof Error;
    }

    /** Resolve @Transactional from the method, then the target class hierarchy. Falls back to
     *  the {@link #DEFAULT} (REQUIRED, no override) if none is found — defensive : the interceptor
     *  is only triggered when @Transactional is present somewhere, so this branch is essentially
     *  unreachable in CDI. */
    private static Transactional resolveAnnotation(InvocationContext ic) {
        Method m = ic.getMethod();
        if (m != null) {
            Transactional ann = m.getAnnotation(Transactional.class);
            if (ann != null) return ann;
        }
        Object target = ic.getTarget();
        if (target != null) {
            for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                Transactional ann = c.getAnnotation(Transactional.class);
                if (ann != null) return ann;
            }
        }
        return DEFAULT;
    }

    private static final Transactional DEFAULT = new DefaultTransactional();

    private record DefaultTransactional() implements Transactional {
        @Override public Class<? extends java.lang.annotation.Annotation> annotationType() {
            return Transactional.class;
        }
        @Override public Transactional.TxType value() { return Transactional.TxType.REQUIRED; }
        @Override public Class[] rollbackOn()     { return new Class[0]; }
        @Override public Class[] dontRollbackOn() { return new Class[0]; }
    }
}
