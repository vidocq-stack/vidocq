/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
/**
 * Exported Knock runtime SPI — stable entry point for integration adapters
 * ({@code knock-cdi-vauban}, {@code knock-cassini}).
 *
 * <p>Contains:</p>
 * <ul>
 *   <li>{@link io.vidocq.knock.runtime.HealthCheckRegistries} — registry factory.</li>
 *   <li>{@link io.vidocq.knock.runtime.KnockHealthService} — aggregation + JSON facade.</li>
 *   <li>{@link io.vidocq.knock.runtime.HealthReport} — HTTP code + JSON body.</li>
 * </ul>
 *
 * <p>Boundary: this package is <strong>exported</strong>. The
 * {@code io.vidocq.knock.internal} package remains unexported; extensions must go
 * exclusively through this runtime SPI.</p>
 */
package io.vidocq.knock.runtime;
