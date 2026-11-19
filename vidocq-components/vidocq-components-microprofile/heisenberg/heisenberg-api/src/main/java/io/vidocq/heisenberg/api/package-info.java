/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
/**
 * Stable public Heisenberg SPI — contracts between {@code heisenberg-core}
 * and {@code heisenberg-cdi-vauban}, and between Heisenberg and external adapters.
 *
 * <p>Planned contents (ROADMAP.md M1+) :</p>
 * <ul>
 *   <li>{@code PolicyContext} — enriched invocation context (method, bean, FT annotations).</li>
 *   <li>{@code RetryConfig}, {@code TimeoutConfig}, {@code CircuitBreakerConfig},
 *       {@code BulkheadConfig} — immutable configurations (Java 25 records).</li>
 *   <li>{@code FaultToleranceException} — the project's base exception.</li>
 * </ul>
 */
package io.vidocq.heisenberg.api;
