package io.vidocq.dirac.internal;

import org.eclipse.microprofile.metrics.MetricRegistry;

import java.lang.management.ManagementFactory;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Enregistre les metriques JVM de base dans le registre BASE.
 */
public final class BaseMetricsRegistrar {
    private final AtomicBoolean registered = new AtomicBoolean();

    public void register(MetricRegistry baseRegistry) {
        var registry = Objects.requireNonNull(baseRegistry, "baseRegistry must not be null");
        if (!registered.compareAndSet(false, true)) {
            return;
        }

        var threadMxBean = ManagementFactory.getThreadMXBean();
        var memoryMxBean = ManagementFactory.getMemoryMXBean();
        var runtimeMxBean = ManagementFactory.getRuntimeMXBean();
        var classLoadingMxBean = ManagementFactory.getClassLoadingMXBean();
        var osMxBean = ManagementFactory.getOperatingSystemMXBean();

        registry.gauge("gc.total", () -> ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(gc -> Math.max(0L, gc.getCollectionCount()))
                .sum());
        registry.gauge("gc.time", () -> ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(gc -> Math.max(0L, gc.getCollectionTime()))
                .sum());

        registry.gauge("thread.count", threadMxBean::getThreadCount);
        registry.gauge("thread.daemon.count", threadMxBean::getDaemonThreadCount);
        registry.gauge("thread.max.count", threadMxBean::getPeakThreadCount);

        registry.gauge("memory.usedHeap", () -> memoryMxBean.getHeapMemoryUsage().getUsed());
        registry.gauge("memory.committedHeap", () -> memoryMxBean.getHeapMemoryUsage().getCommitted());
        registry.gauge("memory.maxHeap", () -> memoryMxBean.getHeapMemoryUsage().getMax());

        registry.gauge("jvm.uptime", runtimeMxBean::getUptime);

        registry.gauge("classloader.loadedClasses", classLoadingMxBean::getLoadedClassCount);
        registry.gauge("classloader.unloadedClasses", classLoadingMxBean::getUnloadedClassCount);

        registry.gauge("cpu.availableProcessors", osMxBean::getAvailableProcessors);
        registry.gauge("cpu.systemLoadAverage", osMxBean::getSystemLoadAverage);
    }
}

