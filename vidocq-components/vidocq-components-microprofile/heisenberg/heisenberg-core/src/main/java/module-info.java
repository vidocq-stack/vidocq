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
 * Pure Java 25 Fault Tolerance policy engines — no CDI dependency.
 *
 * <p>Planned components (cf. ROADMAP.md M1-M7) :</p>
 * <ul>
 *   <li>{@code RetryEngine} — retry state machine with delay, jitter, maxDuration.</li>
 *   <li>{@code TimeoutEngine} — timeout via {@code Thread.ofVirtual() + join(Duration)} (Java 21+, finalized).</li>
 *   <li>{@code CircuitBreakerEngine} — CLOSED/OPEN/HALF_OPEN circuit breaker, sliding window.</li>
 *   <li>{@code BulkheadEngine} — isolation via {@code Semaphore} (sync) or queue (async).</li>
 *   <li>{@code FallbackResolver} — resolves {@code FallbackHandler} or {@code fallbackMethod}
 *       via {@code MethodHandle}.</li>
 *   <li>{@code PolicyComposer} — policy chain in spec §2.5 order.</li>
 *   <li>{@code AnnotationReader} — reads FT annotations on method, then class.</li>
 *   <li>{@code ConfigResolver} — MP Config §9 precedence (method > class > global).</li>
 * </ul>
 */
module io.vidocq.heisenberg.core {
    requires transitive io.vidocq.heisenberg.api;

    // External configuration through MicroProfile Config (Ravel at run time)
    requires org.eclipse.microprofile.config;

    exports io.vidocq.heisenberg.internal to io.vidocq.heisenberg.cdi.vauban;
}
