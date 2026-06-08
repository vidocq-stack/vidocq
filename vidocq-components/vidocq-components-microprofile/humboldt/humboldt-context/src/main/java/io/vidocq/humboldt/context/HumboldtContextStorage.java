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
package io.vidocq.humboldt.context;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextStorage;
import io.opentelemetry.context.Scope;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * Humboldt {@link ContextStorage} implementation — backed by {@link ThreadLocal}.
 *
 * <p>Compliant with the OpenTelemetry contract:</p>
 * <ul>
 *   <li>{@link #current()} returns {@link Context#root()} if no context has been attached;</li>
 *   <li>{@link #attach(Context)} returns a {@link Scope} that, when closed,
 *       restores the previous context (or removes the entry if it was the root);</li>
 *   <li>out-of-order attach/detach calls are tolerated with a {@code WARNING} log
 *       (aligned with the reference OTel implementation — no exception is thrown).</li>
 * </ul>
 *
 * <p><strong>Virtual threads</strong> — since JDK 21 (JEP 444),
 * {@link ThreadLocal} no longer pins the carrier thread during pure Java
 * blocking operations (sleep, NIO I/O, etc.). The only remaining pinning cases
 * involve {@code synchronized}, JNI native code, and some
 * legacy locks — none are introduced here. See PLAN.md §7 for the tracking
 * of a potential migration to {@code ScopedValue} in M8.</p>
 */
public final class HumboldtContextStorage implements ContextStorage {

    static final HumboldtContextStorage INSTANCE = new HumboldtContextStorage();

    private static final Logger LOG = System.getLogger(HumboldtContextStorage.class.getName());
    private static final ThreadLocal<Context> THREAD_LOCAL = new ThreadLocal<>();

    private HumboldtContextStorage() {
        // singleton via ServiceLoader
    }

    @Override
    public Scope attach(Context toAttach) {
        if (toAttach == null) {
            return Scope.noop();
        }
        Context before = THREAD_LOCAL.get();
        if (toAttach == before) {
            return Scope.noop();
        }
        THREAD_LOCAL.set(toAttach);
        return new HumboldtScope(before, toAttach);
    }

    @Override
    public Context current() {
        Context c = THREAD_LOCAL.get();
        return c != null ? c : Context.root();
    }

    private static void detach(Context expected, Context previous) {
        Context actual = THREAD_LOCAL.get();
        if (actual != expected) {
            LOG.log(Level.WARNING,
                    "Context attach/detach order mismatch — expected {0}, found {1}. "
                            + "Possible cause: out-of-order Scope.close(), or context captured via an uninstrumented third-party library.",
                    expected, actual);
        }
        if (previous == null || previous == Context.root()) {
            THREAD_LOCAL.remove();
        } else {
            THREAD_LOCAL.set(previous);
        }
    }

    private static final class HumboldtScope implements Scope {

        private final Context previous;
        private final Context attached;
        private boolean closed;

        HumboldtScope(Context previous, Context attached) {
            this.previous = previous;
            this.attached = attached;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            detach(attached, previous);
        }
    }
}
