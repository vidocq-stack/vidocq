/**
 * CDI 4.1 bootstrap for Mansart Transactions — exposes the {@link jakarta.transaction.TransactionManager}
 * /{@link jakarta.transaction.UserTransaction}/{@link jakarta.transaction.TransactionSynchronizationRegistry}
 * as CDI beans, declares the {@link jakarta.transaction.Transactional} interceptor (REQUIRED,
 * REQUIRES_NEW, MANDATORY, NEVER, NOT_SUPPORTED, SUPPORTS) and the {@link jakarta.transaction.TransactionScoped}
 * scope. Wiring happens via a {@code BuildCompatibleExtension} compatible with Vauban.
 */
module io.vidocq.mansart.transactions.cdi {
    requires transitive io.vidocq.mansart.transactions.core;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.interceptor;
    requires jakarta.annotation;

    exports io.vidocq.mansart.transactions.cdi;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.mansart.transactions.cdi.MansartTransactionsExtension;
}
