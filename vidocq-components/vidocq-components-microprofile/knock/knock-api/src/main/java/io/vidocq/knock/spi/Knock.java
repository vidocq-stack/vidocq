/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.knock.spi;

/**
 * Static metadata for the Knock implementation.
 *
 * <p>This class remains intentionally minimal at this stage (M0).
 * The SPI will be expanded over the milestones: extension registrar,
 * observability hooks, Vidocq ecosystem integration.</p>
 */
public final class Knock {

    /** Logical name of the implementation. */
    public static final String IMPLEMENTATION_NAME = "knock";

    /** Knock implementation version. */
    public static final String IMPLEMENTATION_VERSION = "0.1.0-SNAPSHOT";

    /** Implemented MicroProfile Health spec version. */
    public static final String SPEC_VERSION = "4.0";

    private Knock() {
        // utility class
    }
}
