/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.knock.runtime;

import io.vidocq.knock.internal.KnockHealthCheckRegistry;
import io.vidocq.knock.spi.HealthCheckRegistry;

/**
 * Exported factory for Knock {@link HealthCheckRegistry}s.
 *
 * <p>Stable entry point for Knock integration modules
 * ({@code knock-cdi-vauban}, {@code knock-cassini}) that need a registry instance
 * without depending on the internal {@code io.vidocq.knock.internal} package
 * (which remains unexported — project JPMS boundary).</p>
 *
 * <p>Implementation: delegates to {@link KnockHealthCheckRegistry} (thread-safe,
 * virtual-thread-friendly).</p>
 */
public final class HealthCheckRegistries {

    private HealthCheckRegistries() {
        // utility class
    }

    /**
     * Creates a new empty Knock {@link HealthCheckRegistry}.
     *
     * @return a new thread-safe, ready-to-use instance
     */
    public static HealthCheckRegistry newRegistry() {
        return new KnockHealthCheckRegistry();
    }
}
