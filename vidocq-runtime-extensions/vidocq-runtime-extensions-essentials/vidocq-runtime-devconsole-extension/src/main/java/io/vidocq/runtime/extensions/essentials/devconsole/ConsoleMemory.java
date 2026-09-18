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
package io.vidocq.runtime.extensions.essentials.devconsole;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What the console remembers from one boot to the next within a JVM: the dev reload loop boots the application
 * again and creates a new console each time, but this module stays in the boot layer, so one instance,
 * {@link #JVM}, outlives every boot. It holds two plain values, never an object of the application, which would
 * keep a previous application in memory.
 */
final class ConsoleMemory {

    /** The memory of this JVM. */
    static final ConsoleMemory JVM = new ConsoleMemory();

    private final AtomicReference<String> printedUrl = new AtomicReference<>();
    private final AtomicInteger boundPort = new AtomicInteger();

    /**
     * Records that a boot listens at {@code url}, and says whether to print it: on the first boot of the JVM, and on
     * a later one only when the URL is not the last one printed, so that a dev reload that keeps its address stays
     * quiet and an open tab keeps working.
     *
     * @param url the URL the console listens at
     * @return whether to print it
     */
    boolean toPrint(String url) {
        return !url.equals(printedUrl.getAndSet(url));
    }

    /**
     * The port to ask for when {@code 0} is configured: the one the previous boot bound, so that an open tab
     * survives the reload, or {@code 0} on the first boot.
     */
    int portForAnyPort() {
        return boundPort.get();
    }

    /** Records the port a boot bound. */
    void bound(int port) {
        boundPort.set(port);
    }
}
