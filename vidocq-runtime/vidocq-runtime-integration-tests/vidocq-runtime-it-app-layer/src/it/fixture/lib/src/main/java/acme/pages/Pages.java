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
package acme.pages;

import java.net.URL;

/** Looks a page up through its own class loader, as a framework built for a flat class path does. */
public final class Pages {

    private Pages() {}

    /** The URL of {@code META-INF/acme/<name>} as this library's loader sees it, or {@code null}. */
    public static URL find(String name) {
        return Pages.class.getClassLoader().getResource("META-INF/acme/" + name);
    }

    /** Starts a daemon poller, as a library with a background thread does; it never stops by itself. */
    public static void startPoller() {
        Thread poller = new Thread(() -> {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "acme-pages-poller");
        poller.setDaemon(true);
        poller.start();
    }
}
