/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.cdi;

import io.vidocq.ravel.cdi.internal.RavelConfigPropertyResolver;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.spi.InjectionPoint;

/**
 * {@link SyntheticBeanCreator} qui résout, au runtime, la valeur d'un point
 * d'injection {@code @ConfigProperty} en déléguant à
 * {@link RavelConfigPropertyResolver}.
 *
 * <p>Doit être une classe {@code public} top-level avec un constructeur
 * sans argument ; n'est pas elle-même un bean (cf. spec CDI 4.1
 * §SyntheticBeanCreator).</p>
 *
 * <p>L'{@link InjectionPoint} courant est obtenu via le paramètre
 * {@link Instance} — pattern standard CDI pour les beans
 * {@code @Dependent}.</p>
 */
public class ConfigPropertySyntheticCreator implements SyntheticBeanCreator<Object> {

    @Override
    public Object create(Instance<Object> lookup, Parameters params) {
        InjectionPoint injectionPoint = lookup.select(InjectionPoint.class).get();
        return RavelConfigPropertyResolver.resolve(injectionPoint);
    }
}

