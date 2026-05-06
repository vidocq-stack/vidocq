package io.vidocq.mansart.transactions.cdi;

import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;

/**
 * CDI 4.1 BuildCompatibleExtension that wires Mansart Transactions into the bean container.
 *
 * <p>Pending implementation (in order):
 * <ol>
 *   <li>{@code @Synthesis} — register synthetic beans for
 *       {@link jakarta.transaction.TransactionManager},
 *       {@link jakarta.transaction.UserTransaction},
 *       {@link jakarta.transaction.TransactionSynchronizationRegistry},
 *       all backed by a single {@link io.vidocq.mansart.transactions.core.MansartTransactionManager}.</li>
 *   <li>Register the {@link jakarta.transaction.Transactional} interceptor implementation
 *       ({@code TransactionalInterceptor}) with the proper {@code @Priority} so it sits ahead
 *       of business interceptors. Cover the six TxType values.</li>
 *   <li>Register the {@link jakarta.transaction.TransactionScoped} scope (custom {@code Context}
 *       implementation) so {@code @TransactionScoped} beans are created and destroyed in lockstep
 *       with the active transaction.</li>
 * </ol>
 *
 * <p>Listed in {@code META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension}
 * (CDI 4.1 standard ServiceLoader contract — Vauban now honours it since 0.1.0-SNAPSHOT).
 */
public final class MansartTransactionsExtension implements BuildCompatibleExtension {
    // Empty for now — phases will be added one at a time, TDD-driven.
}
