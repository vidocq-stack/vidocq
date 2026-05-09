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
 * {@link SyntheticBeanCreator} pour le bean {@link Config} {@code @Default}.
 *
 * <p>Délègue à {@link ConfigProvider#getConfig()} qui résout dynamiquement
 * la {@code Config} associée au {@code TCCL} du container.</p>
 */
public class ConfigSyntheticCreator implements SyntheticBeanCreator<Config> {
    @Override
    public Config create(Instance<Object> lookup, Parameters params) {
        return ConfigProvider.getConfig();
    }
}

