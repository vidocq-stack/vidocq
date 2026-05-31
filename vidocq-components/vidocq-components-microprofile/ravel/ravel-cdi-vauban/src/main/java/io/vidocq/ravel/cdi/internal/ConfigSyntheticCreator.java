/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.cdi.internal;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * {@link SyntheticBeanCreator} for the {@link Config} {@code @Default} bean.
 *
 * <p>Delegates to {@link ConfigProvider#getConfig()} which resolves the
 * {@code Config} bound to the container TCCL.</p>
 */
public class ConfigSyntheticCreator implements SyntheticBeanCreator<Config> {
    @Override
    public Config create(Instance<Object> lookup, Parameters params) {
        return ConfigProvider.getConfig();
    }
}

