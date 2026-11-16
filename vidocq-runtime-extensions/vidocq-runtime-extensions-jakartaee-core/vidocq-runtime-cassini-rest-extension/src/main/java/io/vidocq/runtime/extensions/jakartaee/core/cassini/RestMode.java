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
package io.vidocq.runtime.extensions.jakartaee.core.cassini;

import java.util.Locale;

/**
 * How the application's REST resources are served: mounted on Chappe, or as a Servlet of the Foy deployment.
 *
 * <p>As a Servlet, a request goes through the context path, the filters and the authentication of Foy first, so the
 * resources are protected like any servlet (Jakarta REST 4.0 section 2, Servlet container deployment). On Chappe it
 * bypasses all three. The automatic choice is the Servlet whenever both are there to make it work: the Foy deployment
 * and the {@code cassini-servlet} adapter; otherwise Chappe, as before.
 *
 * <p>Read from {@value #PROPERTY}: {@code auto} (default), {@code chappe}, {@code servlet}.
 */
enum RestMode {
    CHAPPE,
    SERVLET;

    /** The configuration key. */
    static final String PROPERTY = "vidocq.rest.mode";

    private static final String FOY = "io.vidocq.runtime.extensions.jakartaee.web.foy.FoyExtension";
    private static final String ADAPTER = "io.vidocq.cassini.servlet.CassiniServletInitializer";

    /**
     * @param configured          the value of {@value #PROPERTY}, or {@code null}
     * @param adapterPresent      whether {@code cassini-servlet} is there
     * @param servletContainerPresent whether the Foy deployment is there
     * @return the mode to use
     * @throws IllegalArgumentException for a value that names no mode
     * @throws IllegalStateException    when {@code servlet} is asked and cannot run
     */
    static RestMode resolve(String configured, boolean adapterPresent, boolean servletContainerPresent) {
        String value = configured == null ? "" : configured.trim().toLowerCase(Locale.ROOT);
        switch (value) {
            case "", "auto":
                return adapterPresent && servletContainerPresent ? SERVLET : CHAPPE;
            case "chappe":
                return CHAPPE;
            case "servlet":
                if (!servletContainerPresent) {
                    throw new IllegalStateException(PROPERTY + "=servlet, but the Foy Servlet extension is not there:"
                            + " add it, or leave the mode to auto");
                }
                if (!adapterPresent) {
                    throw new IllegalStateException(PROPERTY + "=servlet, but cassini-servlet is not there:"
                            + " add it, or leave the mode to auto");
                }
                return SERVLET;
            default:
                throw new IllegalArgumentException(PROPERTY + "=" + configured + ": one of auto, chappe, servlet");
        }
    }

    /** The mode for this deployment: the configured one, with what {@code loader} sees. */
    static RestMode of(String configured, ClassLoader loader) {
        return resolve(configured, present(loader, ADAPTER), present(loader, FOY));
    }

    /**
     * Whether {@code loader} sees the class {@code name}, on the class path or in a module: the class file is looked up
     * as a resource, which the module system never encapsulates, so nothing is loaded or reflected.
     */
    static boolean present(ClassLoader loader, String name) {
        return loader.getResource(name.replace('.', '/') + ".class") != null;
    }
}
