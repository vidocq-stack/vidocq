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
package io.vidocq.runtime.cli.dev;

/**
 * Builds the JVM debug (JDWP) wiring requested by {@code vidocq dev --debug}.
 *
 * <p>Pure value type — the actual relaunch with the agent argument is the caller's
 * responsibility. The CLI runs the runtime in-process, so it cannot inject the agent
 * into the already-running JVM; instead it prints {@link #hint()} so the developer can
 * relaunch with {@link #agentArgument()} (or rely on {@code vidocq start --debug} from a
 * launcher that forwards it).
 *
 * @param port    JDWP listening port
 * @param suspend whether the JVM should wait for a debugger before running ({@code suspend=y})
 */
public record DebugOptions(int port, boolean suspend) {

    /** Default Vidocq debug port, matching the message shown by {@code start --debug}. */
    public static final int DEFAULT_PORT = 5005;

    public static DebugOptions defaults() {
        return new DebugOptions(DEFAULT_PORT, false);
    }

    public DebugOptions {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Debug port out of range: " + port);
        }
    }

    /** The {@code -agentlib:jdwp=...} JVM argument that opens this debug channel. */
    public String agentArgument() {
        return "-agentlib:jdwp=transport=dt_socket,server=y,suspend="
                + (suspend ? "y" : "n")
                + ",address=*:" + port;
    }

    /** Human-readable hint describing how to connect a debugger. */
    public String hint() {
        String base = "Debug mode active — connect your debugger to port " + port + ".";
        return suspend
                ? base + " The JVM will wait for the debugger before starting (suspend=y)."
                : base;
    }
}
