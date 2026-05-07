package io.vidocq.mansart.transactions.cdi;

import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations;
import jakarta.transaction.TransactionScoped;

/**
 * CDI 4.1 BuildCompatibleExtension that wires the {@link TransactionScoped} scope into the bean
 * container.
 *
 * <p>The {@link TransactionScopedContext} is instantiated by the container via its public no-arg
 * constructor and is then queried for every {@code @TransactionScoped} bean lookup. The context
 * resolves the {@link jakarta.transaction.TransactionManager} lazily through the static accessor
 * on {@link MansartTransactionsProducer} — no injection chicken-and-egg problem.
 *
 * <p>Listed in {@code META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension}.
 * Picked up by Vauban (and any other CDI 4.1-compliant container) on startup.
 *
 * <p>The interceptor classes themselves ({@link TransactionalInterceptor} + 5 subclasses) are
 * regular CDI beans annotated {@code @Interceptor + @Priority} — discovered automatically by the
 * standard bean archive scanning, no BCE wiring required.
 */
public final class MansartTransactionsExtension implements BuildCompatibleExtension {

    @Discovery
    public void registerTransactionScope(MetaAnnotations meta) {
        meta.addContext(TransactionScoped.class, true /* normal scope */, TransactionScopedContext.class);
    }

    /**
     * Declares {@link TransactionScoped} as a trigger annotation so compile-time bean discovery
     * (Vauban's APT) includes user beans annotated {@code @TransactionScoped} in its bean index.
     * Without this, classes annotated only with {@code @TransactionScoped} would be invisible to
     * the build-time scan because the processor only watches a fixed list of standard CDI scopes.
     */
    @Enhancement(types = Object.class, withAnnotations = TransactionScoped.class, withSubtypes = true)
    public void registerTransactionScopedTrigger(ClassConfig clazz) {
        // No-op: the sole purpose is to advertise @TransactionScoped via the BCE
        // @Enhancement(withAnnotations=...) contract so the APT round picks up annotated classes.
    }
}
