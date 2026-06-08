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
        try (Scope s = ctx.makeCurrent()) {
            assertEquals("value-A", Context.current().get(KEY));
        }
        assertNull(Context.current().get(KEY));
    }

    @Test
    void nested_attaches_unwind_in_lifo_order() {
        Context outer = Context.root().with(KEY, "outer");
        Context inner = Context.root().with(KEY, "inner");
        try (Scope s1 = outer.makeCurrent()) {
            assertEquals("outer", Context.current().get(KEY));
            try (Scope s2 = inner.makeCurrent()) {
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

        try (Scope ignored = parent.makeCurrent()) {
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

        try (Scope ignored = parent.makeCurrent()) {
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

    @Test
    void close_is_idempotent() {
        Context ctx = Context.root().with(KEY, "x");
        Scope scope = ctx.makeCurrent();
        scope.close();
        scope.close(); // must not fail or double-pop
        assertNull(Context.current().get(KEY));
    }
}
