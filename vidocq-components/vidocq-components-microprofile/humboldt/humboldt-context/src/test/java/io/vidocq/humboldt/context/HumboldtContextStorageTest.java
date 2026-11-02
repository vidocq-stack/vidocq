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
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumboldtContextStorageTest {

    private static final ContextKey<String> KEY = ContextKey.named("humboldt.test.key");

    @Test
    void provider_is_resolved_via_serviceloader() {
        // If OTel had not found our provider, Context.current() would return
        // the default ThreadLocal implementation — functionally identical. We
        // therefore verify the ServiceLoader binding directly.
        assertSame(HumboldtContextStorage.INSTANCE,
                new HumboldtContextStorageProvider().get());
    }

    @Test
    void current_returns_root_when_nothing_attached() {
        assertSame(Context.root(), Context.current());
    }

    @Test
    void attach_and_close_restores_previous() {
        Context ctx = Context.root().with(KEY, "value-A");
        try (var _ = ctx.makeCurrent()) {
            assertEquals("value-A", Context.current().get(KEY));
        }
        assertNull(Context.current().get(KEY));
    }

    @Test
    void nested_attaches_unwind_in_lifo_order() {
        Context outer = Context.root().with(KEY, "outer");
        Context inner = Context.root().with(KEY, "inner");
        try (var _ = outer.makeCurrent()) {
            assertEquals("outer", Context.current().get(KEY));
            try (var _ = inner.makeCurrent()) {
                assertEquals("inner", Context.current().get(KEY));
            }
            assertEquals("outer", Context.current().get(KEY));
        }
        assertNull(Context.current().get(KEY));
    }

    @Test
    void virtual_thread_starts_with_root_context() throws Exception {
        Context parent = Context.root().with(KEY, "parent-value");
        AtomicReference<String> seenInChild = new AtomicReference<>("UNSET");
        CountDownLatch done = new CountDownLatch(1);

        try (var _ = parent.makeCurrent()) {
            // Without explicit wrapping, a new VT must NOT inherit the parent Context.
            Thread.ofVirtual().start(() -> {
                seenInChild.set(Context.current().get(KEY));
                done.countDown();
            });
            assertTrue(done.await(2, TimeUnit.SECONDS), "VT did not finish in time");
        }
        assertNull(seenInChild.get(),
                "VT must NOT see parent Context without wrap (ThreadLocal isolation)");
    }

    @Test
    void context_wrap_propagates_to_virtual_thread() throws Exception {
        Context parent = Context.root().with(KEY, "wrapped-value");
        AtomicReference<String> seenInChild = new AtomicReference<>("UNSET");
        CountDownLatch done = new CountDownLatch(1);

        try (var _ = parent.makeCurrent()) {
            // With Context.wrap(Runnable), OTel reattaches the captured Context
            // in the child VT.
            Runnable task = Context.current().wrap(() -> {
                seenInChild.set(Context.current().get(KEY));
                done.countDown();
            });
            Thread.ofVirtual().start(task);
            assertTrue(done.await(2, TimeUnit.SECONDS), "VT wrap did not complete in time");
        }
        assertEquals("wrapped-value", seenInChild.get(),
                "Context.wrap() must propagate captured context to VT");
    }

    /** Closes the outer scope while an inner one that is never closed is still attached; returns what was logged. */
    private static List<Entry> outOfOrderClose(boolean debug) {
        List<Entry> logged = new ArrayList<>();
        System.Logger recording = new System.Logger() {
            @Override public String getName() { return "recording"; }
            @Override public boolean isLoggable(System.Logger.Level level) { return true; }
            @Override public void log(System.Logger.Level level, ResourceBundle bundle, String msg, Throwable thrown) {
                logged.add(new Entry(level, msg, thrown));
            }
            @Override public void log(System.Logger.Level level, ResourceBundle bundle, String format, Object... params) {
                logged.add(new Entry(level, params == null || params.length == 0 ? format : MessageFormat.format(format, params), null));
            }
        };
        System.Logger previousLogger = HumboldtContextStorage.useLogger(recording);
        boolean previousDebug = HumboldtContextStorage.debug(debug);
        try {
            Scope outer = Context.root().with(KEY, "outer").makeCurrent();
            Context.root().with(KEY, "inner").makeCurrent(); // never closed, as after a call that got no response
            outer.close();
        } finally {
            HumboldtContextStorage.debug(previousDebug);
            HumboldtContextStorage.useLogger(previousLogger);
        }
        return logged;
    }

    private record Entry(System.Logger.Level level, String message, Throwable thrown) { }

    @Test
    void out_of_order_close_warns_and_names_the_debug_property() {
        List<Entry> logged = outOfOrderClose(false);

        assertEquals(1, logged.size());
        assertEquals(System.Logger.Level.WARNING, logged.getFirst().level());
        assertTrue(logged.getFirst().message().contains("order mismatch"));
        assertTrue(logged.getFirst().message().contains("-Dhumboldt.context.debug=true"),
                "the warning says how to get the stacks");
        assertNull(logged.getFirst().thrown(), "no stack is built unless asked for");
        assertNull(Context.current().get(KEY), "the storage recovers: the thread ends up without context");
    }

    @Test
    void out_of_order_close_logs_the_detach_and_attach_stacks_under_the_debug_property() {
        List<Entry> logged = outOfOrderClose(true);

        assertEquals(1, logged.size());
        Throwable detach = logged.getFirst().thrown();
        assertTrue(detach != null && detach.getMessage().contains("detached here"));
        assertEquals(1, detach.getSuppressed().length);
        assertTrue(detach.getSuppressed()[0].getMessage().contains("attached here"),
                "the stack of the attach that was not closed comes with it");
        assertTrue(Arrays.stream(detach.getSuppressed()[0].getStackTrace())
                        .anyMatch(f -> f.getMethodName().equals("outOfOrderClose")),
                "the attach stack reaches the code that attached");
    }

    @Test
    void in_order_close_logs_nothing_under_the_debug_property() {
        List<Entry> logged = new ArrayList<>();
        System.Logger recording = new System.Logger() {
            @Override public String getName() { return "recording"; }
            @Override public boolean isLoggable(System.Logger.Level level) { return true; }
            @Override public void log(System.Logger.Level level, ResourceBundle bundle, String msg, Throwable thrown) {
                logged.add(new Entry(level, msg, thrown));
            }
            @Override public void log(System.Logger.Level level, ResourceBundle bundle, String format, Object... params) {
                logged.add(new Entry(level, format, null));
            }
        };
        System.Logger previousLogger = HumboldtContextStorage.useLogger(recording);
        boolean previousDebug = HumboldtContextStorage.debug(true);
        try (var _ = Context.root().with(KEY, "outer").makeCurrent()) {
            try (var _ = Context.root().with(KEY, "inner").makeCurrent()) {
                assertEquals("inner", Context.current().get(KEY));
            }
        } finally {
            HumboldtContextStorage.debug(previousDebug);
            HumboldtContextStorage.useLogger(previousLogger);
        }
        assertTrue(logged.isEmpty());
    }

    @Test
    void close_is_idempotent() {
        Context ctx = Context.root().with(KEY, "x");
        Scope scope = ctx.makeCurrent();
        scope.close();
        scope.close(); // must not fail or double-pop
        assertNull(Context.current().get(KEY));
    }
}
