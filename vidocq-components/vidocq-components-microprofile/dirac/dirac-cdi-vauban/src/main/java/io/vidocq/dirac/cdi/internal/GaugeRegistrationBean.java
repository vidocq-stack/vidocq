package io.vidocq.dirac.cdi.internal;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Relie les métriques découvertes par la BCE aux registres ciblés par leur scope au démarrage CDI.
 * Pré-enregistre les gauges, timers et compteurs avant tout appel de méthode.
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
