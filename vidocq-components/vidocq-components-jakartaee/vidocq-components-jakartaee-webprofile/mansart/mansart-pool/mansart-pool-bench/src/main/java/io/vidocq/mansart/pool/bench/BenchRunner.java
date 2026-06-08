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
package io.vidocq.mansart.pool.bench;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/**
 * Entry point for {@code mvn exec:java}. Two profiles via the {@code mansart.bench.profile}
 * system property:
 * <ul>
 *   <li>{@code quick} (default) — 1 fork, 2×1s warmup, 3×1s measurement. ~30s total. For local
 *       smoke checks; numbers are indicative only.</li>
 *   <li>{@code full} — 5 forks, 5×3s warmup, 10×3s measurement. ~10min. Use this for any
 *       chiffre that lands in {@code BENCH.md}.</li>
 * </ul>
 */
public final class BenchRunner {
    public static void main(String[] args) throws Exception {
        boolean full = "full".equalsIgnoreCase(System.getProperty("mansart.bench.profile", "quick"));

        OptionsBuilder b = new OptionsBuilder();
        b.include(BorrowReleaseBench.class.getSimpleName());
        b.include(ConcurrentBorrowBench.class.getSimpleName());
        if (full) {
            b.forks(5)
             .warmupIterations(5).warmupTime(TimeValue.seconds(3))
             .measurementIterations(10).measurementTime(TimeValue.seconds(3));
        } else {
            b.forks(1)
             .warmupIterations(2).warmupTime(TimeValue.seconds(1))
             .measurementIterations(3).measurementTime(TimeValue.seconds(1));
        }
        Options opt = b.build();
        new Runner(opt).run();
    }
}
