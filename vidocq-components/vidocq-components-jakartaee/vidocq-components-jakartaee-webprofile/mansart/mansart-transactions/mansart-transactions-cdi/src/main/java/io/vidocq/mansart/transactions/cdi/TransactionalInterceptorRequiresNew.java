package io.vidocq.mansart.transactions.cdi;

import jakarta.annotation.Priority;
import jakarta.interceptor.Interceptor;
import jakarta.transaction.Transactional;

/**
 * Interceptor binding for {@code @Transactional(REQUIRES_NEW)}. Pure binding — all logic is
 * inherited from {@link TransactionalInterceptor#invoke}.
 *
 * <p>One subclass per {@link Transactional.TxType} is required because
 * {@link Transactional#value()} is NOT {@code @Nonbinding} in jakarta.transaction-api 2.0.x —
 * each TxType therefore counts as a distinct interceptor binding from CDI's perspective.
 */
@Interceptor
@Transactional(Transactional.TxType.REQUIRES_NEW)
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 200)
public class TransactionalInterceptorRequiresNew extends TransactionalInterceptor {}
