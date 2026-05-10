/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.knock.spi;

/**
 * Type de probe MicroProfile Health 4.0.
 *
 * <p>Correspond aux quatre endpoints définis par la spec §3 :</p>
 * <ul>
 *   <li>{@link #LIVENESS}  → {@code GET /health/live}</li>
 *   <li>{@link #READINESS} → {@code GET /health/ready}</li>
 *   <li>{@link #STARTUP}   → {@code GET /health/started}</li>
 *   <li>{@link #ALL}       → {@code GET /health} (agrégat de tous les checks)</li>
 * </ul>
 *
 * @see <a href="https://download.eclipse.org/microprofile/microprofile-health-4.0/microprofile-health-spec-4.0.html#_health_endpoints">
 *      MicroProfile Health 4.0 §3</a>
 */
public enum ProbeType {

    /** {@code @Liveness} checks — endpoint {@code /health/live}. */
    LIVENESS,

    /** {@code @Readiness} checks — endpoint {@code /health/ready}. */
    READINESS,

    /** {@code @Startup} checks — endpoint {@code /health/started}. */
    STARTUP,

    /** Agrégat de tous les checks — endpoint {@code /health}. */
    ALL
}
