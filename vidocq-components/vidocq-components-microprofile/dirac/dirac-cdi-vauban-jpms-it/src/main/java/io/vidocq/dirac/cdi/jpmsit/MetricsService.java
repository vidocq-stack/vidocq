package io.vidocq.dirac.cdi.jpmsit;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.metrics.annotation.Counted;

/**
 * A {@code @Counted} bean whose {@code $$Intercepted} subclass is generated at BUILD time by the
 * Vauban APT (the DiracExtension BCE, on the annotation-processor path, registers {@code @Counted} as
 * an interceptor binding).
 *
 * <p>It carries {@code @Counted} at <strong>both</strong> levels on purpose:
 * <ul>
 *   <li><b>class level</b> makes the constructor an intercepted target, so {@code @AroundConstruct}
 *       on {@code CountedInterceptor} fires — proving the construct-interception chain runs on the
 *       module path with no {@code opens} (the interceptor is instantiated in-module by the generated
 *       provider, and its public {@code @AroundConstruct} is reachable without opens). That callback's
 *       {@code preRegisterCounters} is what registers the counter below;</li>
 *   <li><b>method level</b> on {@link #ping()} drives {@code @AroundInvoke} and pins the asserted
 *       metric id ({@code jpmsit.counted.calls}).</li>
 * </ul>
 *
 * <p>Used by {@code MetricsModulePathTest} to prove that {@code @Counted} interception — both
 * {@code @AroundConstruct} and {@code @AroundInvoke} — fires on the module path with no {@code opens}:
 * the bean AND the {@code CountedInterceptor} are instantiated and field-injected in-module by the
 * generated {@code VaubanComponentProvider}.</p>
 */
@ApplicationScoped
@Counted
public class MetricsService {

    @Counted(name = "jpmsit.counted.calls", absolute = true, tags = {"source=jpms"})
    public String ping() {
        return "pong";
    }
}
