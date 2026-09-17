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
 * The child JVM of {@link ConsoleLoggingProcessTest}: goes through the embedders' install point,
 * then logs {@value #RECORDS} records the way Vidocq's bricks do, through {@code System.Logger}, plus
 * one through {@code java.util.logging}.
 */
public final class ConsoleLoggingChildMain {

    static final int RECORDS = 5;

    private ConsoleLoggingChildMain() {}

    public static void main(String[] args) throws InterruptedException {
        VidocqBootstrap.create();
        System.Logger log = System.getLogger("io.vidocq.runtime.core.console.Child");
        log.log(System.Logger.Level.INFO, "Vidocq - Configuration phase");
        log.log(System.Logger.Level.WARNING, "A warning with its stack trace",
                new IllegalStateException("No McpServerSPI implementation found."));
        log.log(System.Logger.Level.ERROR, "An error");
        Thread.ofVirtual().start(() -> log.log(System.Logger.Level.INFO, "Logged from a virtual thread")).join();
        java.util.logging.Logger.getLogger("child.jul").info("Logged through java.util.logging");
    }
}
