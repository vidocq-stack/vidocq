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
package io.vidocq.runtime.extensions.jakartaee.web.foy;

import io.vidocq.runtime.extensions.essentials.chappe.ChappeListener;
import io.vidocq.runtime.spi.VidocqConfiguration;

/**
 * The Servlet extension's configuration.
 *
 * @param contextPath           {@code vidocq.servlet.context-path}; {@code null} lets Foy use the
 *                              {@code web.xml} {@code <default-context-path>}, else {@code /}
 * @param listener              {@code vidocq.servlet.listener}: the Chappe listener of the mount
 * @param sessionTimeoutSeconds {@code vidocq.servlet.session-timeout}: used when no descriptor
 *                              declares a {@code <session-timeout>}
 */
record ServletSettings(String contextPath, String listener, int sessionTimeoutSeconds) {

    static final String CONTEXT_PATH = "vidocq.servlet.context-path";
    static final String LISTENER = "vidocq.servlet.listener";
    static final String SESSION_TIMEOUT = "vidocq.servlet.session-timeout";
    static final int DEFAULT_SESSION_TIMEOUT_SECONDS = 30 * 60;

    static ServletSettings from(VidocqConfiguration config) {
        String timeout = config.property(SESSION_TIMEOUT).orElse(null);
        return new ServletSettings(config.property(CONTEXT_PATH).orElse(null),
                config.property(LISTENER, ChappeListener.DEFAULT), seconds(timeout));
    }

    /** {@code null}: the default; otherwise a number of seconds, named by its key when it is not one. */
    static int seconds(String timeout) {
        if (timeout == null) return DEFAULT_SESSION_TIMEOUT_SECONDS;
        try {
            return Integer.parseInt(timeout.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(SESSION_TIMEOUT + " must be a number of seconds, got '"
                    + timeout + "'", e);
        }
    }
}
