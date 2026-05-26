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

        // Explicit class exclusion has highest priority.
        if (excludeClasses.contains(className)) {
            return false;
        }

        // Explicit class inclusion can override package-level excludes.
        if (includeClasses.contains(className)) {
            return true;
        }

        String packageName = extractPackageName(className);
        int includePackageMatch = bestPackageMatch(includePackages, packageName);
        int excludePackageMatch = bestPackageMatch(excludePackages, packageName);
        boolean hasIncludes = !includeClasses.isEmpty() || !includePackages.isEmpty();

        if (!hasIncludes) {
            return excludePackageMatch < 0;
        }

        if (includePackageMatch < 0) {
            return false;
        }

        // For overlapping include/exclude package filters, the most specific package wins.
        return includePackageMatch > excludePackageMatch;
    }

    private String extractPackageName(String className) {
        int lastDot = className.lastIndexOf('.');
        return lastDot > 0 ? className.substring(0, lastDot) : "";
    }

    private int bestPackageMatch(Set<String> packages, String packageName) {
        int best = -1;
        for (String candidate : packages) {
            if (packageName.equals(candidate) || packageName.startsWith(candidate + ".")) {
                best = Math.max(best, candidate.length());
            }
        }
        return best;
    }
}

