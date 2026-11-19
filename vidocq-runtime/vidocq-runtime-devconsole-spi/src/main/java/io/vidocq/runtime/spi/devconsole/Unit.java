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
package io.vidocq.runtime.spi.devconsole;

/**
 * What a sampled number measures, which tells the page how to format it and how to read its
 * {@linkplain Series.Style#RATE rate}.
 */
public enum Unit {

    /** A number of things, such as connections, threads or borrows: shown as it is. */
    COUNT,

    /** A size in bytes, such as the heap in use: shown as a size. */
    BYTES,

    /**
     * A time in nanoseconds, shown as a duration. The {@linkplain Series.Style#RATE rate} of a counter of
     * nanoseconds is the share of wall time spent, such as the time a garbage collector paused the application.
     */
    NANOS,

    /** A fraction from 0 to 1, such as a CPU load: shown as a percentage. */
    RATIO
}
