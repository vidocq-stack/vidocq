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
    // Compile-only (optional at runtime): supplies the VaubanComponentProvider service type.
    requires static io.vidocq.vauban.api;

    exports io.vidocq.mansart.transactions.cdi;

    // The producer and the @TransactionScoped context are instantiated, field-injected and have their
    // producer methods invoked in-module by the APT-generated _VaubanComponents provider below (no
    // reflection). The six @Transactional *interceptors*, however, are NOT yet emitted into that
    // provider: the Vauban APT excludes @Interceptor classes from its component set, so on the module
    // path the container still instantiates them reflectively. This qualified opens is therefore the
    // single residual reflection surface — see mansart-transactions-cdi-jpms-it and the Vauban gap
    // "interceptors not emitted into _VaubanComponents" (BUG.md). It collapses to zero once the APT
    // emits @Interceptor beans as components.
    opens io.vidocq.mansart.transactions.cdi to io.vidocq.vauban.core;

    // In-module instantiation, field injection and producer invocation of this package's beans (the
    // six @Transactional interceptors, the TM/UT/TSR producer and the @TransactionScoped context),
    // generated as _VaubanComponents — APT-generated, inert under Weld.
    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.mansart.transactions.cdi._VaubanComponents;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.mansart.transactions.cdi.MansartTransactionsExtension;
}
