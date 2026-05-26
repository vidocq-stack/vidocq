package io.vidocq.grimm.internal.config;

import java.util.Set;

/**
 * Configuration for annotation scanning.
 *
 * Spec §4.1: MP Config keys for scanning:
 * - mp.openapi.scan.disable
 * - mp.openapi.scan.packages
 * - mp.openapi.scan.classes
 * - mp.openapi.scan.exclude.packages
 * - mp.openapi.scan.exclude.classes
 */
public record ScanConfig(
    boolean disableScan,
    Set<String> includePackages,
    Set<String> includeClasses,
    Set<String> excludePackages,
    Set<String> excludeClasses
) {
    /**
     * Creates a default {@code ScanConfig} with scanning enabled and no filters.
     */
    public static ScanConfig defaultConfig() {
        return new ScanConfig(
            false,
            Set.of(),
            Set.of(),
            Set.of(),
            Set.of()
        );
    }

    /**
     * Checks if the given class should be scanned.
     *
     * @param className the fully-qualified class name
     * @return true if the class should be scanned, false otherwise
     */
    public boolean shouldScan(String className) {
        if (disableScan) {
            return false;
        }

        // Check explicit class exclusion first
        if (excludeClasses.contains(className)) {
            return false;
        }

        // Check package exclusion
        String packageName = extractPackageName(className);
        if (isPackageExcluded(packageName)) {
            return false;
        }

        // If include lists are empty, include all (except excluded above)
        if (includeClasses.isEmpty() && includePackages.isEmpty()) {
            return true;
        }

        // Check explicit class inclusion
        if (includeClasses.contains(className)) {
            return true;
        }

        // Check package inclusion
        return isPackageIncluded(packageName);
    }

    private String extractPackageName(String className) {
        int lastDot = className.lastIndexOf('.');
        return lastDot > 0 ? className.substring(0, lastDot) : "";
    }

    private boolean isPackageIncluded(String packageName) {
        for (String included : includePackages) {
            if (packageName.equals(included) || packageName.startsWith(included + ".")) {
                return true;
            }
        }
        return false;
    }

    private boolean isPackageExcluded(String packageName) {
        for (String excluded : excludePackages) {
            if (packageName.equals(excluded) || packageName.startsWith(excluded + ".")) {
                return true;
            }
        }
        return false;
    }
}

