package io.vidocq.mansart.transactions.cdi;

import jakarta.annotation.Priority;
import jakarta.interceptor.Interceptor;
import jakarta.transaction.Transactional;

/** See {@link TransactionalInterceptorRequiresNew} — same rationale. */
@Interceptor
@Transactional(Transactional.TxType.SUPPORTS)
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 200)
public class TransactionalInterceptorSupports extends TransactionalInterceptor {}
