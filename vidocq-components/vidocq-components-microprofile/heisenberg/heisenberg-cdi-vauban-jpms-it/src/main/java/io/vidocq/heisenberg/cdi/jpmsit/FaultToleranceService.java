package io.vidocq.heisenberg.cdi.jpmsit;

import java.util.concurrent.atomic.AtomicInteger;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.faulttolerance.Retry;

/**
 * A {@code @Retry} bean whose {@code $$Intercepted} subclass is generated at BUILD time by the Vauban
 * APT (the HeisenbergExtension BCE, on the annotation-processor path, adds the {@code @FaultToleranceBinding}
 * marker to {@code @Retry}-annotated elements via {@code @Enhancement}).
 *
 * <p>Used by {@code FaultToleranceModulePathTest} to prove that {@code @Retry} interception fires on the
 * module path with no {@code opens}: the bean AND the {@code FaultToleranceInterceptor} are instantiated
 * and field-injected in-module by the generated {@code VaubanComponentProvider} (the interceptor's
 * {@code @Inject} state-registry fields are package-private, so the in-package {@code putfield} needs no
 * opens), and the interceptor's public {@code @AroundInvoke} runs the retry policy without opens.</p>
 */
@ApplicationScoped
public class FaultToleranceService {

    public static final AtomicInteger CALLS = new AtomicInteger(0);

    /** {@code @Retry(maxRetries = 3)} → 1 initial call + 3 retries = 4 invocations before it gives up. */
    @Retry(maxRetries = 3, retryOn = RuntimeException.class, delay = 0L)
    public String call() {
        CALLS.incrementAndGet();
        throw new IllegalStateException("boom");
    }
}
