package io.vidocq.mansart.transactions.cdi;

import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionScoped;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.UserTransaction;

/**
 * CDI 4.1 BuildCompatibleExtension that wires the {@link TransactionScoped} scope into the bean
 * container and exposes the three Jakarta Transactions singletons ({@link TransactionManager},
 * {@link UserTransaction}, {@link TransactionSynchronizationRegistry}) as synthetic beans.
 *
 * <p>The {@link TransactionScopedContext} is instantiated by the container via its public no-arg
 * constructor and is then queried for every {@code @TransactionScoped} bean lookup. The context
 * resolves the {@link TransactionManager} lazily through the static accessor on
 * {@link MansartTransactionsProducer} — no injection chicken-and-egg problem.
 *
 * <p>The three transactions singletons are exposed via {@code @Synthesis} synthetic beans rather
 * than through a real {@code @Produces} class shipped with this jar: a real producer class would
 * either need to be scanned via {@code ScannedClasses.add(...)} (which makes Vauban-processor
 * generate a {@code *_Factory.class} in the producer's package within the user module's output,
 * triggering a JPMS split-package between the user module and {@code io.vidocq.mansart.transactions.cdi})
 * or be discovered through bean-archive scanning (which never reaches dependency JARs in
 * APT-driven containers like Vauban). The synthetic-bean route lives only in the bean index.
 *
 * <p>Listed in {@code META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension}.
 * Picked up by Vauban (and any other CDI 4.1-compliant container) on startup.
 *
 * <p>The interceptor classes themselves ({@link TransactionalInterceptor} + 5 subclasses) are
 * regular CDI beans annotated {@code @Interceptor + @Priority} — discovered automatically by the
 * standard bean archive scanning, no BCE wiring required.
 */
public final class MansartTransactionsExtension implements BuildCompatibleExtension {

    private boolean transactionManagerAlreadyDeclared;

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

    /**
     * Detects whether a {@link TransactionManager} bean is already declared in the deployment.
     * <p>This guards against double-registration when the BCE is replayed at runtime by Vauban:
     * the {@code @Synthesis} pass at compile time already serialised a synthetic
     * {@code TransactionManager} into the bean index, and the runtime pass would otherwise add
     * a second one — surfacing as
     * {@code Ambiguous dependency: ... of type TransactionManager}. The {@code @Registration}
     * phase runs before {@code @Synthesis} and exposes every bean already in the deployment,
     * including those serialised from a prior compile-time pass. We only need to track
     * {@link TransactionManager} — if it has already been added, we skip all three synthetic
     * beans (they are added together).
     */
    @Registration(types = TransactionManager.class)
    public void detectExistingTransactionManager(BeanInfo bean) {
        transactionManagerAlreadyDeclared = true;
    }

    /**
     * Registers default {@link TransactionManager} / {@link UserTransaction} /
     * {@link TransactionSynchronizationRegistry} synthetic beans, all backed by the same
     * {@link MansartTransactionsProducer#tm()} singleton. Skipped when the BCE is replayed on a
     * deployment whose compile-time pass already declared them (cf. {@link #detectExistingTransactionManager}).
     */
    @Synthesis
    public void registerTransactionsBeans(SyntheticComponents components) {
        if (transactionManagerAlreadyDeclared) {
            return;
        }
        components.<TransactionManager>addBean(TransactionManager.class)
                .type(TransactionManager.class)
                .scope(jakarta.inject.Singleton.class)
                .createWith(DefaultTransactionManagerCreator.class);
        components.<UserTransaction>addBean(UserTransaction.class)
                .type(UserTransaction.class)
                .scope(jakarta.inject.Singleton.class)
                .createWith(DefaultUserTransactionCreator.class);
        components.<TransactionSynchronizationRegistry>addBean(TransactionSynchronizationRegistry.class)
                .type(TransactionSynchronizationRegistry.class)
                .scope(jakarta.inject.Singleton.class)
                .createWith(DefaultTransactionSynchronizationRegistryCreator.class);
    }
}
