package io.vidocq.grimm.internal.config;

/**
 * Configuration for model filtering and reading.
 *
 * Spec §4.1: MP Config keys:
 * - mp.openapi.filter
 * - mp.openapi.model.reader
 */
public record FilterConfig(
    String filterClassName,
    String modelReaderClassName
) {
    /**
     * Creates a default {@code FilterConfig} with no filters or readers.
     */
    public static FilterConfig defaultConfig() {
        return new FilterConfig(null, null);
    }

    /**
     * Checks if a filter is configured.
     */
    public boolean hasFilter() {
        return filterClassName != null && !filterClassName.isEmpty();
    }

    /**
     * Checks if a model reader is configured.
     */
    public boolean hasModelReader() {
        return modelReaderClassName != null && !modelReaderClassName.isEmpty();
    }
}

