package io.vidocq.mansart.transactions.cdi;

import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionScoped;

import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * CDI {@link AlterableContext} for {@link TransactionScoped} — instances live exactly as long
 * as the underlying transaction.
 *
 * <p>Storage is keyed by the active {@link Transaction} (one map per TX, computed lazily on
 * first {@code @TransactionScoped} bean access). At completion, an auto-registered
 * {@link Synchronization} destroys every {@code @TransactionScoped} instance attached to the
 * TX before dropping the map.
 *
 * <p>Why not store via {@link jakarta.transaction.TransactionSynchronizationRegistry#putResource}
 * — TSR exposes the resources publicly, while CDI scope state must stay opaque to user code.
 * Keeping a private map is also faster (no boxing of Contextual into an arbitrary key).
 */
public final class TransactionScopedContext implements AlterableContext {

    private final ConcurrentMap<Transaction, ConcurrentMap<Contextual<?>, Holder<?>>> store =
            new ConcurrentHashMap<>();

    /**
     * Public no-arg constructor — required by the CDI 4.1 BCE
     * {@code MetaAnnotations.addContext(scope, isNormal, contextClass)} API which instantiates the
     * context via reflection. The {@link TransactionManager} is resolved lazily via
     * {@link MansartTransactionsProducer#tm()} (the same singleton the CDI producer publishes).
     */
    public TransactionScopedContext() {
    }

    private TransactionManager tm() {
        return MansartTransactionsProducer.tm();
    }

    @Override
    public Class<? extends Annotation> getScope() {
        return TransactionScoped.class;
    }

    @Override
    public boolean isActive() {
        try {
            int s = tm().getStatus();
            return s == Status.STATUS_ACTIVE || s == Status.STATUS_MARKED_ROLLBACK;
        } catch (SystemException e) {
            return false;
        }
    }

    @Override
    public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
        Transaction tx = requireActiveTx();
        ConcurrentMap<Contextual<?>, Holder<?>> map = store.computeIfAbsent(tx, this::registerCleanup);
        @SuppressWarnings("unchecked")
        Holder<T> existing = (Holder<T>) map.get(contextual);
        if (existing != null) return existing.instance;
        if (creationalContext == null) return null;
        T instance = contextual.create(creationalContext);
        map.put(contextual, new Holder<>(instance, creationalContext));
        return instance;
    }

    @Override
    public <T> T get(Contextual<T> contextual) {
        Transaction tx = requireActiveTx();
        ConcurrentMap<Contextual<?>, Holder<?>> map = store.get(tx);
        if (map == null) return null;
        @SuppressWarnings("unchecked")
        Holder<T> h = (Holder<T>) map.get(contextual);
        return h == null ? null : h.instance;
    }

    @Override
    public void destroy(Contextual<?> contextual) {
        Transaction tx = requireActiveTx();
        ConcurrentMap<Contextual<?>, Holder<?>> map = store.get(tx);
        if (map == null) return;
        Holder<?> h = map.remove(contextual);
        if (h != null) destroyHolder(contextual, h);
    }

    private ConcurrentMap<Contextual<?>, Holder<?>> registerCleanup(Transaction tx) {
        ConcurrentMap<Contextual<?>, Holder<?>> map = new ConcurrentHashMap<>();
        try {
            tx.registerSynchronization(new Synchronization() {
                @Override public void beforeCompletion() { /* no-op */ }
                @Override public void afterCompletion(int status) {
                    ConcurrentMap<Contextual<?>, Holder<?>> dead = store.remove(tx);
                    if (dead == null) return;
                    dead.forEach((c, h) -> destroyHolder(c, h));
                }
            });
        } catch (Exception ignored) {
            // TX is already terminating — destroy will run via the next get() / explicit destroy().
        }
        return map;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void destroyHolder(Contextual c, Holder h) {
        try {
            c.destroy(h.instance, h.creationalContext);
        } catch (RuntimeException ignored) {
            // Spec §6.3.4 — destroy must not propagate ; we swallow per-bean to ensure
            // sibling beans still get a chance to release.
        }
    }

    private Transaction requireActiveTx() {
        try {
            Transaction tx = tm().getTransaction();
            if (tx == null) {
                throw new ContextNotActiveException(
                        "@TransactionScoped accessed outside an active transaction");
            }
            return tx;
        } catch (SystemException e) {
            throw new ContextNotActiveException(e.getMessage());
        }
    }

    private record Holder<T>(T instance, CreationalContext<T> creationalContext) {}
}
