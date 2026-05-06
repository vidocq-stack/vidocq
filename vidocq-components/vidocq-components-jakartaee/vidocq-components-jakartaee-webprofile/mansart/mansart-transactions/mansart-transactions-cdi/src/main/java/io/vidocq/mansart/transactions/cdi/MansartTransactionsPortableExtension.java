package io.vidocq.mansart.transactions.cdi;

import io.vidocq.mansart.transactions.core.MansartTransactionManager;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.BeforeBeanDiscovery;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionScoped;

/**
 * Classic CDI 4.x portable {@link Extension} that registers the {@link TransactionScopedContext}.
 *
 * <p>CDI 4.1 BCE (Build-Compatible Extensions) cannot register custom {@link jakarta.enterprise.context.spi.Context}
 * implementations — only synthetic beans, qualifiers, scopes, stereotypes. Contexts still go
 * through the legacy {@code Extension} SPI (which is fully supported by Weld, OpenWebBeans,
 * and most CDI Full containers).
 *
 * <p>Vauban (CDI Lite-only) does not honour the legacy Extension SPI. The {@code @TransactionScoped}
 * scope under Vauban will require a Vauban-side hook landing in M7b — see {@code mansart-transactions/PLAN.md}.
 *
 * <p>Listed in {@code META-INF/services/jakarta.enterprise.inject.spi.Extension} alongside
 * the BCE provider — both files coexist without conflict.
 */
public final class MansartTransactionsPortableExtension implements Extension {

    /**
     * Make sure the container recognises {@link TransactionScoped} as a scope (most do
     * automatically because the annotation is meta-annotated {@code @NormalScope}, but
     * declaring it explicitly is harmless and helps containers that check the registry).
     */
    void registerScope(@Observes BeforeBeanDiscovery event) {
        event.addScope(TransactionScoped.class, true /* normal */, false /* not passivating */);
    }

    /**
     * Register the {@link TransactionScopedContext}. Wired against the shared
     * {@link MansartTransactionManager} accessor because the context is registered BEFORE
     * the producer's TM is injectable.
     */
    void registerContext(@Observes AfterBeanDiscovery event) {
        TransactionManager tm = MansartTransactionsProducer.tm();
        event.addContext(new TransactionScopedContext(tm));
    }
}
