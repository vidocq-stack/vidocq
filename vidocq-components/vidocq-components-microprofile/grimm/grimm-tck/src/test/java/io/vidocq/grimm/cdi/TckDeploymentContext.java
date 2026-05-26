package io.vidocq.grimm.cdi;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shares the current Arquillian deployment classes with TCK-only producers.
 */
public final class TckDeploymentContext {

    private static final AtomicReference<List<Class<?>>> DISCOVERED_TYPES = new AtomicReference<>(List.of());
    private static final AtomicReference<Map<String, String>> CONFIG = new AtomicReference<>(Map.of());

    private TckDeploymentContext() {
    }

    public static void setDiscoveredTypes(List<Class<?>> types) {
        DISCOVERED_TYPES.set(List.copyOf(types));
    }

    public static List<Class<?>> discoveredTypes() {
        return DISCOVERED_TYPES.get();
    }

    public static void setConfig(Map<String, String> config) {
        CONFIG.set(Map.copyOf(config));
    }

    public static Map<String, String> config() {
        return CONFIG.get();
    }

    public static void clear() {
        DISCOVERED_TYPES.set(List.of());
        CONFIG.set(Map.of());
    }
}

