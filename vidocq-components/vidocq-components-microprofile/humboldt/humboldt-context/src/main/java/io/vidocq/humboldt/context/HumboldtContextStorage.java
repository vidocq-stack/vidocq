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
import java.util.concurrent.atomic.AtomicReference;

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
 * <p><strong>Finding an out-of-order close</strong> — the warning names the two contexts but not the code that
 * left a scope open. With {@code -Dhumboldt.context.debug=true} the storage records, for every attach, the
 * stack it was made from, and the warning carries the stack of the detach with the stack of the attach that was
 * never closed as a suppressed exception. The recording costs one exception per attach, so it is off by default.</p>
 *
 * <p><strong>Virtual threads</strong> — since JDK 21 (JEP 444),
 * {@link ThreadLocal} no longer pins the carrier thread during pure Java
 * blocking operations (sleep, NIO I/O, etc.). The only remaining pinning cases
 * involve {@code synchronized}, JNI native code, and some
 * legacy locks — none are introduced here. See PLAN.md §7 for the tracking
 * of a potential migration to {@code ScopedValue} in M8.</p>
 */
public final class HumboldtContextStorage implements ContextStorage {

    /** System property that turns on the attach and detach stacks of the order mismatch warning. */
    static final String DEBUG_PROPERTY = "humboldt.context.debug";

    static final HumboldtContextStorage INSTANCE = new HumboldtContextStorage();

    private static final AtomicReference<Logger> LOG =
            new AtomicReference<>(System.getLogger(HumboldtContextStorage.class.getName()));
    private static final ThreadLocal<Context> THREAD_LOCAL = new ThreadLocal<>();
    /** The stack the current context was attached from; only set while {@link #debug} is on. */
    private static final ThreadLocal<Throwable> ATTACH_SITE = new ThreadLocal<>();

    private static volatile boolean debug = Boolean.getBoolean(DEBUG_PROPERTY);

    private HumboldtContextStorage() {
        // singleton via ServiceLoader
    }

    /** Replaces the logger the warnings go to, and returns the previous one (tests). */
    static Logger useLogger(Logger logger) {
        return LOG.getAndSet(logger);
    }

    /** Turns the attach and detach stacks on or off, and returns the previous setting (tests). */
    static boolean debug(boolean on) {
        boolean previous = debug;
        debug = on;
        return previous;
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
        if (!debug) {
            return new HumboldtScope(before, toAttach, null, null);
        }
        Throwable previousSite = ATTACH_SITE.get();
        Throwable site = new IllegalStateException("Context attached here");
        ATTACH_SITE.set(site);
        return new HumboldtScope(before, toAttach, site, previousSite);
    }

    @Override
    public Context current() {
        Context c = THREAD_LOCAL.get();
        return c != null ? c : Context.root();
    }

    private static final class HumboldtScope implements Scope {

        private final Context previous;
        private final Context attached;
        /** The stack of this attach, and the one of the context it replaced; {@code null} without the debug property. */
        private final Throwable site;
        private final Throwable previousSite;
        private boolean closed;

        HumboldtScope(Context previous, Context attached, Throwable site, Throwable previousSite) {
            this.previous = previous;
            this.attached = attached;
            this.site = site;
            this.previousSite = previousSite;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            detach(attached, previous);
            if (site != null) {
                restoreSite(previousSite);
            }
        }

        private static void restoreSite(Throwable previousSite) {
            if (previousSite == null) {
                ATTACH_SITE.remove();
            } else {
                ATTACH_SITE.set(previousSite);
            }
        }

        private static void detach(Context expected, Context previous) {
            Context actual = THREAD_LOCAL.get();
            if (actual != expected) {
                warnOutOfOrder(expected, actual);
            }
            if (previous == null || previous == Context.root()) {
                THREAD_LOCAL.remove();
            } else {
                THREAD_LOCAL.set(previous);
            }
        }

        private static void warnOutOfOrder(Context expected, Context actual) {
            String message = "Context attach/detach order mismatch — expected " + expected + ", found " + actual
                    + ". Possible cause: out-of-order Scope.close(), or context captured via an uninstrumented"
                    + " third-party library. Run with -D" + DEBUG_PROPERTY + "=true to log the stacks of the attach"
                    + " that was not closed and of this detach.";
            Throwable stacks = null;
            if (debug) {
                stacks = new IllegalStateException("Context detached here");
                Throwable openSite = ATTACH_SITE.get();
                if (openSite != null) {
                    stacks.addSuppressed(openSite);
                }
            }
            if (stacks == null) {
                LOG.get().log(Level.WARNING, message);
            } else {
                LOG.get().log(Level.WARNING, message, stacks);
            }
        }
    }
}
