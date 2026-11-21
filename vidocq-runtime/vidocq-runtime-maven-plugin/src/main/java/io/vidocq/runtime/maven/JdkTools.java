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
package io.vidocq.runtime.maven;

import java.io.IOException;
import java.io.PrintStream;
import java.util.spi.ToolProvider;

/**
 * Runs a tool of the JDK ({@code jdeps}, {@code jlink}, {@code jpackage}) inside the Maven JVM, one at a time.
 *
 * <p>A parallel build ({@code -T}) runs the image goals of several modules at once, in one JVM, and the {@code jlink}
 * of JDK 25 is not reentrant: of six images linked at once, five fail with
 * {@code IllegalStateException: stream has already been operated upon or closed}. The tools of the build's goals
 * therefore share one lock; an image takes a few seconds, so they wait little.
 */
final class JdkTools {

    private static final Object ONE_AT_A_TIME = new Object();

    private JdkTools() {
    }

    /**
     * Runs the tool of that name, its output on the console as when it runs on its own, and returns its exit code.
     *
     * @throws IOException when the JDK that runs Maven has no such tool
     */
    @SuppressWarnings("java:S106") // the tool's own output, line for line: what jlink or jpackage prints in a shell
    static int run(String name, String... args) throws IOException {
        return run(name, System.out, System.err, args);
    }

    /**
     * Runs the tool of that name, after any other tool this plugin runs, and returns its exit code.
     *
     * @throws IOException when the JDK that runs Maven has no such tool
     */
    static int run(String name, PrintStream out, PrintStream err, String... args) throws IOException {
        ToolProvider tool = ToolProvider.findFirst(name)
                .orElseThrow(() -> new IOException(name + " is not available in this JDK"));
        synchronized (ONE_AT_A_TIME) {
            return tool.run(out, err, args);
        }
    }
}
