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
package io.vidocq.runtime.extensions.essentials.devconsole;

import io.vidocq.runtime.spi.devconsole.Chart;
import io.vidocq.runtime.spi.devconsole.DevConsolePanel;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.devconsole.Series;
import io.vidocq.runtime.spi.devconsole.Unit;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportSection;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * The console's own {@code jvm} panel, shown last: the JVM the application runs on, read from the platform MXBeans,
 * the same beans Dirac's base gauges read, so the numbers are the same and the console depends on nothing more.
 *
 * <ul>
 *   <li><b>Boot facts:</b> {@code java} (the version and the vendor), {@code vm}, {@code gc} (the collectors),
 *       {@code heap max}, {@code processors}, {@code pid}, and {@code source JMX}. No JVM argument and no system
 *       property: a dev service passes a password as {@code -D}.</li>
 *   <li><b>Live values:</b> the heap used, with the heap max as its max, or what is committed when the JVM has none;
 *       the heap committed; the non-heap used; the threads, daemon and peak; the classes loaded; the uptime; the CPU
 *       load of the process and of the system, as ratios; the system load average. One group per garbage collector:
 *       its collections and the time it took, a counter of nanoseconds, the pause time for G1, Parallel and
 *       Serial.</li>
 *   <li><b>Charts:</b> memory, non-heap, threads, CPU, and, per collector, the share of time it took and the
 *       collections per second.</li>
 * </ul>
 *
 * <p>The CPU loads are the attributes {@code ProcessCpuLoad} and {@code CpuLoad} of the platform operating system
 * bean, which only {@code com.sun.management.OperatingSystemMXBean} declares. The panel reads them through that
 * interface's public getters, found once, when the JVM has the {@code jdk.management} module, and requires nothing
 * more than {@code java.management}: the platform {@code MBeanServer} would give the same values, at the price of
 * registering every platform bean, some 50 ms, on the first poll. A figure the JVM does not publish, or publishes as a
 * negative number, is written absent, {@code not available}, never as a zero.
 *
 * <p>{@link #sample} reads memory only, the counters the JVM keeps, through beans found once: no lock the application
 * may hold, no I/O of its own. Nothing here refers to the application.
 */
final class JvmPanel implements DevConsolePanel {

    /** The panel's id, reserved by the core for the console. */
    static final String ID = "jvm";

    private static final String NOT_AVAILABLE = "not available";
    private static final long MIB = 1024L * 1024;
    private static final long GIB = 1024L * MIB;
    private static final List<Chart> CHARTS = List.of(
            new Chart("memory", "Memory",
                    List.of(Series.area("heap.used"), Series.line("heap.committed"), Series.ceiling("heap.used"))),
            new Chart("nonheap", "Non-heap", List.of(Series.area("nonheap.used"))),
            new Chart("threads", "Threads", List.of(Series.line("threads"), Series.line("threads.daemon"))),
            new Chart("cpu", "CPU load", List.of(Series.line("cpu.process"), Series.line("cpu.system"))),
            new Chart("gc", "Garbage collection", List.of(Series.rate("time"), Series.rate("collections"))));

    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final ClassLoadingMXBean classes = ManagementFactory.getClassLoadingMXBean();
    private final RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
    private final List<GarbageCollectorMXBean> collectors = List.copyOf(ManagementFactory.getGarbageCollectorMXBeans());
    private final DoubleSupplier processCpu;
    private final DoubleSupplier systemCpu;
    private final DoubleSupplier loadAverage;

    /** The panel of this JVM, its CPU loads read from the platform operating system bean when it publishes them. */
    JvmPanel() {
        OperatingSystemMXBean system = ManagementFactory.getOperatingSystemMXBean();
        this.processCpu = platformFigure(system, "getProcessCpuLoad");
        this.systemCpu = platformFigure(system, "getCpuLoad");
        this.loadAverage = system::getSystemLoadAverage;
    }

    /**
     * @param processCpu  the CPU load of the process, 0 to 1; negative or not a number when unknown
     * @param systemCpu   the CPU load of the system, 0 to 1; negative or not a number when unknown
     * @param loadAverage the system load average; negative or not a number when unknown
     */
    JvmPanel(DoubleSupplier processCpu, DoubleSupplier systemCpu, DoubleSupplier loadAverage) {
        this.processCpu = Objects.requireNonNull(processCpu, "processCpu");
        this.systemCpu = Objects.requireNonNull(systemCpu, "systemCpu");
        this.loadAverage = Objects.requireNonNull(loadAverage, "loadAverage");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "JVM";
    }

    /** The JVM, once per boot: what it is, its collectors, its heap max, its processors, its pid. */
    @Override
    public void contribute(StartupReportContext context, StartupReportSection section) {
        String heapMax = size(memory.getHeapMemoryUsage().getMax());
        int processors = Runtime.getRuntime().availableProcessors();
        section.summary("Java " + Runtime.version() + ", " + processors + " processors, heap max " + heapMax)
                .row("java", Runtime.version() + ", " + runtime.getVmVendor())
                .row("vm", runtime.getVmName() + " " + runtime.getVmVersion())
                .list("gc", collectors.stream().map(GarbageCollectorMXBean::getName).toList())
                .row("heap max", heapMax)
                .row("processors", processors)
                .row("pid", runtime.getPid())
                .row("source", "JMX");
    }

    @Override
    public List<Chart> charts() {
        return CHARTS;
    }

    /** The values the JVM keeps now, then one group per collector. */
    @Override
    public void sample(PanelSample sample) {
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
        sample.gauge("heap.used", heap.getUsed(), ceiling(heap), Unit.BYTES)
                .gauge("heap.committed", heap.getCommitted(), Unit.BYTES)
                .gauge("nonheap.used", nonHeap.getUsed(), nonHeap.getMax(), Unit.BYTES)
                .gauge("threads", threads.getThreadCount(), Unit.COUNT)
                .gauge("threads.daemon", threads.getDaemonThreadCount(), Unit.COUNT)
                .gauge("threads.peak", threads.getPeakThreadCount(), Unit.COUNT)
                .gauge("classes", classes.getLoadedClassCount(), Unit.COUNT)
                .duration("uptime", Duration.ofMillis(runtime.getUptime()));
        ratio(sample, "cpu.process", read(processCpu));
        ratio(sample, "cpu.system", read(systemCpu));
        double load = read(loadAverage);
        if (load >= 0) {
            sample.gauge("load", load, Unit.COUNT);
        } else {
            sample.absent("load", NOT_AVAILABLE);
        }
        for (GarbageCollectorMXBean collector : collectors) {
            PanelSample group = sample.group(collector.getName());
            long count = collector.getCollectionCount();
            long millis = collector.getCollectionTime();
            if (count < 0 || millis < 0) {
                group.absent("collections", NOT_AVAILABLE).absent("time", NOT_AVAILABLE);
            } else {
                group.counter("collections", count, Unit.COUNT)
                        .counter("time", millis * 1_000_000L, Unit.NANOS);
            }
        }
    }

    /**
     * The most the heap can reach: its max, or what is committed when the JVM sets no max.
     *
     * @param heap the heap's usage
     * @return the ceiling, in bytes
     */
    static long ceiling(MemoryUsage heap) {
        return heap.getMax() < 0 ? heap.getCommitted() : heap.getMax();
    }

    /**
     * A size as the boot facts write it: {@code 512 B}, {@code 64 KB}, {@code 1024 MB} up to 10 GB, then
     * {@code 30.0 GB}; {@code not set} for a negative size, which the JVM gives for a max it does not define.
     *
     * @param bytes the size in bytes
     * @return the size to read
     */
    static String size(long bytes) {
        if (bytes < 0) {
            return "not set";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < MIB) {
            return bytes / 1024 + " KB";
        }
        if (bytes < 10 * GIB) {
            return bytes / MIB + " MB";
        }
        return String.format(Locale.ROOT, "%.1f GB", (double) bytes / GIB);
    }

    private static void ratio(PanelSample sample, String key, double value) {
        if (value >= 0) {
            sample.gauge(key, value, 1, Unit.RATIO);
        } else {
            sample.absent(key, NOT_AVAILABLE);
        }
    }

    /** The figure, or {@code NaN} when reading it fails: a figure that cannot be read is absent. */
    private static double read(DoubleSupplier figure) {
        try {
            return figure.getAsDouble();
        } catch (RuntimeException unreadable) {
            return Double.NaN;
        }
    }

    /**
     * A getter of {@code com.sun.management.OperatingSystemMXBean} on the platform bean, or a figure that is never
     * available when the JVM has no {@code jdk.management} module or its bean does not implement that interface.
     * The lookup is the public one, which needs no {@code requires}: the package is exported to everyone.
     */
    private static DoubleSupplier platformFigure(OperatingSystemMXBean system, String getter) {
        MethodHandle handle;
        try {
            Class<?> extended = ModuleLayer.boot().findModule("jdk.management")
                    .map(module -> Class.forName(module, "com.sun.management.OperatingSystemMXBean"))
                    .orElse(null);
            if (extended == null || !extended.isInstance(system)) {
                return () -> Double.NaN;
            }
            handle = MethodHandles.publicLookup().findVirtual(extended, getter, MethodType.methodType(double.class))
                    .bindTo(system);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return () -> Double.NaN;
        }
        return () -> {
            try {
                return (double) handle.invoke();
            } catch (RuntimeException | Error thrown) {
                throw thrown;
            } catch (Throwable impossible) {
                return Double.NaN;
            }
        };
    }
}
