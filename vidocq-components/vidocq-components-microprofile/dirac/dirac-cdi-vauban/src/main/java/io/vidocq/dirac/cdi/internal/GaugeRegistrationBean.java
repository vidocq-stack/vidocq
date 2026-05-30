package io.vidocq.dirac.cdi.internal;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Connects the metrics discovered by the BCE to the registries targeted by their scope at CDI startup.
 * Pre-registers gauges, timers, and counters before any method call.
 */
@ApplicationScoped
public class GaugeRegistrationBean {
    @Inject
    MetricRegistryProducerBean registries;

    private final AtomicBoolean registered = new AtomicBoolean();

    @PostConstruct
    void registerMetrics() {
        doRegister();
    }

    void onApplicationStart(@Observes @Initialized(ApplicationScoped.class) Object ignored) {
        doRegister();
    }

    private void doRegister() {
        if (!registered.compareAndSet(false, true)) {
            return;
        }
        DiracExtension.registerDiscoveredGauges(
                registries,
                beanClass -> CDI.current().select(beanClass).get()
        );
        DiracExtension.registerDiscoveredTimers(registries);
        DiracExtension.registerDiscoveredCounters(registries);
    }
}
