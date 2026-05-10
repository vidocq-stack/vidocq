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
 * Bean CDI {@link HealthCheckRegistry} — singleton applicatif injectable via
 * {@code @Inject HealthCheckRegistry}.
 *
 * <p>Délègue à un registry obtenu par la factory SPI
 * {@link HealthCheckRegistries#newRegistry()} (le package interne
 * {@code io.vidocq.knock.internal} n'est pas exporté — frontière JPMS du projet).
 * Exposé en tant que bean {@code @ApplicationScoped} implémentant l'interface
 * {@link HealthCheckRegistry} pour que le proxy CDI implémente directement
 * l'interface et soit castable sans ambiguïté.</p>
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
