/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.knock.internal;

import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.spi.HealthCheckResponseProvider;

/**
 * ServiceLoader SPI — provides the {@link KnockHealthCheckResponseBuilder} to
 * {@link org.eclipse.microprofile.health.HealthCheckResponse#named(String)}.
 *
 * <p>Registered via:</p>
 * <ul>
 *   <li>{@code META-INF/services/org.eclipse.microprofile.health.spi.HealthCheckResponseProvider}</li>
 *   <li>{@code provides ... with} in {@code module-info.java}</li>
 * </ul>
 */
public final class KnockHealthCheckResponseProvider implements HealthCheckResponseProvider {

    @Override
    public HealthCheckResponseBuilder createResponseBuilder() {
        return new KnockHealthCheckResponseBuilder();
    }
}
