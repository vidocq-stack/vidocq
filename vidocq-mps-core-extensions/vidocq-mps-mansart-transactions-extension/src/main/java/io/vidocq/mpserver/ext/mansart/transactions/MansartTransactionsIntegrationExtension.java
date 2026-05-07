package io.vidocq.mpserver.ext.mansart.transactions;

import io.vidocq.mpserver.spi.ExtensionContext;
import io.vidocq.mpserver.spi.VidocqExtension;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.transaction.TransactionManager;

import java.util.Set;

/**
 * Glue between Vidocq lifecycle and the Mansart Transactions stack.
 *
 * <p>This extension does <b>not</b> register the {@code mansart-transactions-cdi} BCE — Vauban
 * picks it up via the standard CDI 4.1 ServiceLoader contract
 * ({@code META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension}).
 * Its only job is a fail-fast sanity check at {@code onStart}: if the
 * {@link TransactionManager} bean is not visible to the CDI container, the boot is aborted with a
 * clear error message rather than failing later on the first {@code @Transactional} call site.
 *
 * <p>Priority {@code 250} — runs after the pool extension (200) which publishes the
 * {@code DataSource}, but before mansart-data (300) so the {@code @Transactional} interceptors
 * are guaranteed to be visible by the time repositories start accepting calls.
 */
public final class MansartTransactionsIntegrationExtension implements VidocqExtension {

    private static final System.Logger LOG =
            System.getLogger(MansartTransactionsIntegrationExtension.class.getName());

    @Override
    public String name() {
        return "mansart-transactions";
    }

    @Override
    public int priority() {
        return 250;
    }

    @Override
    public void onStart(ExtensionContext context) {
        BeanManager bm = context.beanManager();
        Set<Bean<?>> tmBeans = bm.getBeans(TransactionManager.class);
        if (tmBeans.isEmpty()) {
            throw new IllegalStateException(
                    "Mansart Transactions extension is enabled but no TransactionManager bean is "
                            + "exposed by CDI. Did mansart-transactions-cdi reach the runtime "
                            + "classpath, and was its BuildCompatibleExtension picked up by the "
                            + "container?");
        }
        LOG.log(System.Logger.Level.INFO, "Mansart Transactions: TransactionManager wired");
    }
}
