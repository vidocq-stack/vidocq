/**
 * CDI 4.1 bootstrap for Mansart Transactions — exposes the {@link jakarta.transaction.TransactionManager}
 * /{@link jakarta.transaction.UserTransaction}/{@link jakarta.transaction.TransactionSynchronizationRegistry}
 * as CDI beans, declares the {@link jakarta.transaction.Transactional} interceptor (REQUIRED,
 * REQUIRES_NEW, MANDATORY, NEVER, NOT_SUPPORTED, SUPPORTS) and the {@link jakarta.transaction.TransactionScoped}
 * scope.
 *
 * <p>Wired via the CDI 4.1 BuildCompatibleExtension API
 * ({@link io.vidocq.mansart.transactions.cdi.MansartTransactionsExtension}).
 * The {@link jakarta.transaction.TransactionScoped} scope is registered using
 * {@code MetaAnnotations.addContext(scope, isNormal, contextClass)} which Vauban (and any
 * CDI 4.1-compliant container) honours natively.
 */
module io.vidocq.mansart.transactions.cdi {
    requires transitive io.vidocq.mansart.transactions.core;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.interceptor;
    requires jakarta.annotation;

    exports io.vidocq.mansart.transactions.cdi;

    // CDI containers reflect on the producer + interceptor + context types.
    opens io.vidocq.mansart.transactions.cdi;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.mansart.transactions.cdi.MansartTransactionsExtension;
}
