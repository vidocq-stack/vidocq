/**
 * Vidocq extension that fails fast on boot if Mansart Transactions has not wired its
 * {@link jakarta.transaction.TransactionManager} bean into CDI.
 *
 * <p>The actual {@code @Transactional} interceptor and {@code @TransactionScoped} context come
 * from {@code mansart-transactions-cdi}, auto-discovered by Vauban via
 * {@code META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension}.
 * This extension does not register the BCE itself; it only validates wiring at boot.
 */
module io.vidocq.mpserver.ext.mansart.transactions {
    requires transitive io.vidocq.mpserver.spi;
    requires io.vidocq.vauban.core;
    requires transitive io.vidocq.mansart.transactions.cdi;
    requires jakarta.cdi;

    provides io.vidocq.mpserver.spi.VidocqExtension
            with io.vidocq.mpserver.ext.mansart.transactions.MansartTransactionsIntegrationExtension;
}
