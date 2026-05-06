/**
 * CDI 4.1 bootstrap for Mansart Transactions — exposes the {@link jakarta.transaction.TransactionManager}
 * /{@link jakarta.transaction.UserTransaction}/{@link jakarta.transaction.TransactionSynchronizationRegistry}
 * as CDI beans, declares the {@link jakarta.transaction.Transactional} interceptor (REQUIRED,
 * REQUIRES_NEW, MANDATORY, NEVER, NOT_SUPPORTED, SUPPORTS) and the {@link jakarta.transaction.TransactionScoped}
 * scope.
 *
 * <p>Two extension SPIs are provided in parallel :
 * <ul>
 *   <li>{@link io.vidocq.mansart.transactions.cdi.MansartTransactionsExtension} — CDI 4.1
 *       BuildCompatibleExtension (Vauban, Quarkus-style discovery).</li>
 *   <li>{@link io.vidocq.mansart.transactions.cdi.MansartTransactionsPortableExtension} —
 *       legacy portable Extension (Weld, OpenWebBeans). Required for context registration
 *       which BCE does not yet cover in CDI 4.1.</li>
 * </ul>
 */
module io.vidocq.mansart.transactions.cdi {
    requires transitive io.vidocq.mansart.transactions.core;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.interceptor;
    requires jakarta.annotation;

    exports io.vidocq.mansart.transactions.cdi;

    // Weld reflects on the producer + interceptor + context types via setAccessible.
    opens io.vidocq.mansart.transactions.cdi;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.mansart.transactions.cdi.MansartTransactionsExtension;
    provides jakarta.enterprise.inject.spi.Extension
            with io.vidocq.mansart.transactions.cdi.MansartTransactionsPortableExtension;
}
