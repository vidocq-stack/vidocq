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
 * {@link SyntheticBeanCreator} resolving {@code @ConfigProperty} injection point
 * values at runtime through {@link RavelConfigPropertyResolver}.
 *
 * <p>Must be a top-level {@code public} class with a no-arg constructor;
 * it is not itself a CDI bean (CDI 4.1 SyntheticBeanCreator contract).</p>
 *
 * <p>The current {@link InjectionPoint} is obtained through the
 * {@link Instance} parameter.</p>
 */
public class ConfigPropertySyntheticCreator implements SyntheticBeanCreator<Object> {

    @Override
    public Object create(Instance<Object> lookup, Parameters params) {
        InjectionPoint injectionPoint = lookup.select(InjectionPoint.class).get();
        return RavelConfigPropertyResolver.resolve(injectionPoint);
    }
}

