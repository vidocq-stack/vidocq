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
 * APT annotation processor generating {@code <Bean>$$DiracMetrics} companion sources
 * at compile time for every class carrying {@code @Timed}/{@code @Counted}/{@code @Gauge}
 * (codegen audit CG-05 — APT-first rule). Metric names, units, tags and scopes are
 * resolved at compile time with the exact same rules as the {@code DiracExtension}
 * startup scan; gauges become direct functional accessors (no
 * {@code MethodHandles.privateLookupIn}, no {@code opens}).
 *
 * <p>Any construct that cannot be emitted faithfully (private gauge method, invalid
 * gauge signature, nested bean class…) is skipped with a compiler NOTE — the startup
 * scan remains the documented fallback and preserves exact runtime behaviour,
 * including deployment errors the TCK expects.</p>
 */
module io.vidocq.dirac.processor {
    requires java.compiler;
    // Transitively provides microprofile.metrics.api (@Timed/@Counted/@Gauge).
    requires io.vidocq.dirac.api;

    // No exports: an APT processor is consumed exclusively through the
    // javax.annotation.processing.Processor SPI below (javac ServiceLoader).
    provides javax.annotation.processing.Processor
            with io.vidocq.dirac.processor.DiracMetricsProcessor;
}
