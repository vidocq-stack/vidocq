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

import java.nio.charset.Charset;

/**
 * The Servlet extension's configuration.
 *
 * @param contextPath           {@code vidocq.servlet.context-path}; {@code null} lets Foy use the
 *                              {@code web.xml} {@code <default-context-path>}, else {@code /}
 * @param listener              {@code vidocq.servlet.listener}: the Chappe listener of the mount
 * @param sessionTimeoutSeconds {@code vidocq.servlet.session-timeout}: used when no descriptor
 *                              declares a {@code <session-timeout>}
 * @param responseCharacterEncoding {@code vidocq.servlet.response-character-encoding}: the context's
 *                              default response encoding when no descriptor declares a
 *                              {@code <response-character-encoding>}, UTF-8 unless configured;
 *                              an initializer or a listener can still set another one
 */
record ServletSettings(String contextPath, String listener, int sessionTimeoutSeconds,
                       String responseCharacterEncoding) {

    static final String CONTEXT_PATH = "vidocq.servlet.context-path";
    static final String LISTENER = "vidocq.servlet.listener";
    static final String SESSION_TIMEOUT = "vidocq.servlet.session-timeout";
    static final String RESPONSE_CHARACTER_ENCODING = "vidocq.servlet.response-character-encoding";
    static final int DEFAULT_SESSION_TIMEOUT_SECONDS = 30 * 60;
    /** Not the Servlet default (ISO-8859-1): a page written through {@code getWriter()} keeps its accents. */
    static final String DEFAULT_RESPONSE_CHARACTER_ENCODING = "UTF-8";

    static ServletSettings from(VidocqConfiguration config) {
        String timeout = config.property(SESSION_TIMEOUT).orElse(null);
        return new ServletSettings(config.property(CONTEXT_PATH).orElse(null),
                config.property(LISTENER, ChappeListener.DEFAULT), seconds(timeout),
                encoding(config.property(RESPONSE_CHARACTER_ENCODING).orElse(null)));
    }

    /**
     * {@code null}: the default, UTF-8; otherwise the canonical name of the charset, named by its key
     * when the JDK does not know it.
     */
    static String encoding(String name) {
        if (name == null) return DEFAULT_RESPONSE_CHARACTER_ENCODING;
        try {
            return Charset.forName(name.trim()).name();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(RESPONSE_CHARACTER_ENCODING
                    + " must name a character encoding the JDK supports, got '" + name + "'", e);
        }
    }

    /** {@code null}: the default; otherwise a number of seconds, named by its key when it is not one. */
    static int seconds(String timeout) {
        return seconds(SESSION_TIMEOUT, timeout, DEFAULT_SESSION_TIMEOUT_SECONDS);
    }

    static int seconds(String key, String timeout, int defaultSeconds) {
        if (timeout == null) return defaultSeconds;
        try {
            return Integer.parseInt(timeout.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number of seconds, got '" + timeout + "'", e);
        }
    }
}
