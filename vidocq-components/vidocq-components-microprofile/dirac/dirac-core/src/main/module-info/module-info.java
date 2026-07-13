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
 * Pure Java 25 implementations of the MicroProfile Metrics 5.1.1 metrics — no CDI dependency.
 *
 * <p>Planned components (see ROADMAP.md M1-M6) :</p>
 * <ul>
 *   <li>{@code CounterImpl} — incremental counter via {@code LongAdder}.</li>
 *   <li>{@code GaugeImpl} — instant value via {@code MethodHandle} resolved at startup.</li>
 *   <li>{@code HistogramImpl} — EWMA reservoir with percentiles, thread-safe via {@code AtomicLongArray}.</li>
 *   <li>{@code TimerImpl} — call duration via {@code System.nanoTime()} + {@code HistogramImpl}.</li>
 *   <li>{@code MetricRegistryImpl} — thread-safe registry by scope ({@code ConcurrentHashMap}).</li>
 *   <li>{@code OpenMetricsFormatter} — Prometheus text 0.0.4 format serialization.</li>
 *   <li>{@code JsonMetricsFormatter} — JSON serialization in MP Metrics spec §3.2 format.</li>
 *   <li>{@code BaseMetricsRegistrar} — mandatory JVM metrics (GC, threads, heap, uptime).</li>
 * </ul>
 *
 * <p><strong>Java Modules note — testCompile workaround</strong> :
 * This {@code module-info.java} is in {@code src/main/module-info/} (not
 * {@code src/main/java/}) so that Maven Compiler Plugin does not detect Java Modules during
 * {@code testCompile}. {@code maven-clean-plugin} deletes {@code module-info.class} before
 * {@code testCompile} (incremental builds). A {@code prepare-package} execution
 * recompiles {@code module-info.java} alone. Tests run on the classpath
 * ({@code useModulePath=false}) — Java Modules wiring is validated by the smoke TCK.</p>
 */
module io.vidocq.dirac.core {
    requires transitive io.vidocq.dirac.api;
    // BaseMetricsRegistrar reads the JVM MXBeans (GC, threads, heap, uptime) for the BASE registry.
    requires java.management;

    exports io.vidocq.dirac.internal to io.vidocq.dirac.cdi.vauban, io.vidocq.dirac.rest;
}
