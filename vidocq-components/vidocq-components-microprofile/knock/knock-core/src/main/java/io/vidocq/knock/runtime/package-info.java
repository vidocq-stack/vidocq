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
 * SPI runtime exportée de Knock — point d'entrée stable pour les adaptateurs
 * d'intégration ({@code knock-cdi-vauban}, {@code knock-cassini}).
 *
 * <p>Contient :</p>
 * <ul>
 *   <li>{@link io.vidocq.knock.runtime.HealthCheckRegistries} — factory de registries.</li>
 *   <li>{@link io.vidocq.knock.runtime.KnockHealthService} — façade agrégation + JSON.</li>
 *   <li>{@link io.vidocq.knock.runtime.HealthReport} — code HTTP + corps JSON.</li>
 * </ul>
 *
 * <p>Frontière : ce package est <strong>exporté</strong>. Le package
 * {@code io.vidocq.knock.internal} reste non exporté ; les extensions doivent passer
 * exclusivement par cette SPI runtime.</p>
 */
package io.vidocq.knock.runtime;

