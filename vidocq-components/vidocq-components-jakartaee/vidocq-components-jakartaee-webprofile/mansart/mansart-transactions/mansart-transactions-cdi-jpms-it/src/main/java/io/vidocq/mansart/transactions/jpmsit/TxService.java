package io.vidocq.mansart.transactions.jpmsit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.TransactionManager;

/**
 * A {@code @Transactional} bean whose {@code $$Intercepted} subclass is generated at BUILD time by
 * the Vauban APT ({@code jakarta.transaction.Transactional} is an {@code @InterceptorBinding}).
 *
 * <p>Used by {@code TxModulePathIT} to prove that {@code @Transactional} interception fires on the
 * module path with no {@code opens} directive and no runtime-generated subclass: the container
 * instantiates the build-time subclass and field-injects {@link #tm} through the generated
 * {@code _VaubanComponents} provider, entirely in-module.</p>
 */
@ApplicationScoped
public class TxService {

    /** Package-private so the generated provider can field-inject it in-module (no {@code opens}). */
    @Inject
    TransactionManager tm;

    @Transactional(Transactional.TxType.REQUIRED)
    public void required(Block body) throws Exception {
        body.run();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void requiresNew(Block body) throws Exception {
        body.run();
    }
}
