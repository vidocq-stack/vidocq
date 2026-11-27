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
package io.vidocq.runtime.core.console;

import io.vidocq.runtime.core.VidocqBootstrap;

/**
 * The child JVM of {@link ConsoleLoggingProcessTest} for the exit: installs Vidocq's console, then exits with a
 * shutdown hook that logs once the JDK's own logging hook ({@code LogManager}'s {@code Logging-Cleaner}, which resets
 * every logger) has ended, as Vidocq's hook does when the stop of an application takes a while.
 */
public final class ConsoleLoggingShutdownChildMain {

    static final String LAST_LINE = "Vidocq - Shutting down";

    private ConsoleLoggingShutdownChildMain() {}

    public static void main() {
        VidocqBootstrap.create();
        System.Logger log = System.getLogger("io.vidocq.runtime.core.console.Child");
        log.log(System.Logger.Level.INFO, "Started");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            waitForTheJdkLoggingHook();
            log.log(System.Logger.Level.INFO, LAST_LINE);
        }, "vidocq-shutdown"));
        System.exit(0);
    }

    /**
     * Lets the JDK's logging hook reset every logger first, the order that lost the line. The JVM starts the hooks in no
     * set order: the hook is looked for until it shows, or for two seconds, which only a hook already ended outlasts.
     */
    private static void waitForTheJdkLoggingHook() {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (ConsoleLogHandler.JDK_LOGGING_SHUTDOWN_HOOK.equals(thread.getName())) {
                    join(thread);
                    return;
                }
            }
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }
}
