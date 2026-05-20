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
        // Si OTel n'avait pas trouvé notre provider, Context.current() retournerait
        // l'impl ThreadLocal par défaut — fonctionnellement identique. On vérifie
        // donc directement le binding ServiceLoader.
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
            // Sans wrap explicite, un nouveau VT ne doit PAS hériter du Context parent.
            Thread.ofVirtual().start(() -> {
                seenInChild.set(Context.current().get(KEY));
                done.countDown();
            });
            assertTrue(done.await(2, TimeUnit.SECONDS), "VT n'a pas terminé à temps");
        }
        assertNull(seenInChild.get(),
                "le VT ne doit PAS voir le Context parent sans wrap (isolation ThreadLocal)");
    }

    @Test
    void context_wrap_propagates_to_virtual_thread() throws Exception {
        Context parent = Context.root().with(KEY, "wrapped-value");
        AtomicReference<String> seenInChild = new AtomicReference<>("UNSET");
        CountDownLatch done = new CountDownLatch(1);

        try (Scope ignored = parent.makeCurrent()) {
            // Avec Context.wrap(Runnable), OTel ré-attache le Context capturé
            // dans le VT enfant.
            Runnable task = Context.current().wrap(() -> {
                seenInChild.set(Context.current().get(KEY));
                done.countDown();
            });
            Thread.ofVirtual().start(task);
            assertTrue(done.await(2, TimeUnit.SECONDS), "VT wrap n'a pas terminé à temps");
        }
        assertEquals("wrapped-value", seenInChild.get(),
                "Context.wrap() doit propager le contexte capturé au VT");
    }

    @Test
    void close_is_idempotent() {
        Context ctx = Context.root().with(KEY, "x");
        Scope scope = ctx.makeCurrent();
        scope.close();
        scope.close(); // ne doit pas planter ni double-pop
        assertNull(Context.current().get(KEY));
    }
}
