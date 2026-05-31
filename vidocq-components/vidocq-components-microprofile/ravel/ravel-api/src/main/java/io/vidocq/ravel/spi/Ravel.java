/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.ravel.spi;

/**
 * Static metadata for the Ravel implementation — used by {@code ConfigSource}
 * for tracing and by benchmarks.
 *
 * <p>The SPI content will be expanded over versions:
 * configuration sources, third-party converters, observability hooks. This class
 * is intentionally kept minimal at this stage.</p>
 */
public final class Ravel {

    /** Logical name of the implementation, exposed via {@code Config.getConfigSources()}. */
    public static final String IMPLEMENTATION_NAME = "ravel";

    /** Version of the Ravel implementation. */
    public static final String IMPLEMENTATION_VERSION = "0.1.0-SNAPSHOT";

    /** Version of the MicroProfile Config spec implemented. */
    public static final String SPEC_VERSION = "3.1";

    private Ravel() {
        // utility class
    }
}
