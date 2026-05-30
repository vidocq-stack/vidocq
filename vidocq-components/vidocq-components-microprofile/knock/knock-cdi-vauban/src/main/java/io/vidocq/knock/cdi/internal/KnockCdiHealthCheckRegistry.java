/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.knock.cdi.internal;

import io.vidocq.knock.runtime.HealthCheckRegistries;
import io.vidocq.knock.spi.HealthCheckRegistry;
import io.vidocq.knock.spi.ProbeType;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;

import java.util.List;

/**
 * CDI {@link HealthCheckRegistry} bean — application singleton injectable via
 * {@code @Inject HealthCheckRegistry}.
 *
 * <p>Delegates to a registry obtained from the SPI factory
 * {@link HealthCheckRegistries#newRegistry()} (the internal package
 * {@code io.vidocq.knock.internal} is not exported — project JPMS boundary).
 * Exposed as an {@code @ApplicationScoped} bean implementing the
 * {@link HealthCheckRegistry} interface so that the CDI proxy directly implements
 * the interface and can be cast without ambiguity.</p>
 */
@ApplicationScoped
class KnockCdiHealthCheckRegistry implements HealthCheckRegistry {

    private final HealthCheckRegistry delegate = HealthCheckRegistries.newRegistry();

    @Override
    public void register(ProbeType type, String name, HealthCheck check) {
        delegate.register(type, name, check);
    }

    @Override
    public void unregister(String name) {
        delegate.unregister(name);
    }

    @Override
    public List<HealthCheck> getChecks(ProbeType type) {
        return delegate.getChecks(type);
    }
}
