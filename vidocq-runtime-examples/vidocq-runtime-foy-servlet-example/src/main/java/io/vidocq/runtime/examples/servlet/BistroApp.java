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
package io.vidocq.runtime.examples.servlet;

import io.vidocq.runtime.core.Vidocq;
import io.vidocq.runtime.spi.VidocqApp;
import io.vidocq.runtime.spi.VidocqMain;

/**
 * Entry point of the Vidocq Bistro, a small Jakarta Servlet 6.1 application served by Foy on
 * the Chappe server.
 *
 * <p>The application shows servlets, a filter and a session listener, all CDI beans: a
 * {@code @RequestScoped} bean shared by the filter and the servlets of one request, a
 * {@code @SessionScoped} basket, {@code @ApplicationScoped} services, and an admin page
 * protected by {@code @ServletSecurity} with a FORM login and a sign-out.
 *
 * <p>The class is a {@link VidocqMain} trampoline: {@code main} only calls
 * {@code Vidocq.run(...)}, which re-resolves the application in the Vauban layer and calls
 * {@link #run} there.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * java -m io.vidocq.runtime.examples.servlet/io.vidocq.runtime.examples.servlet.BistroApp
 * }</pre>
 *
 * <p>Then open {@code http://localhost:8080/}. The kitchen page {@code /kitchen} asks for the
 * user {@code chef}, password {@code chef}.
 */
@VidocqMain
public class BistroApp implements VidocqApp {

    static void main(String[] args) {
        Vidocq.run(BistroApp.class, args);
    }

    @Override
    public int run(String... args) throws Exception {
        Vidocq.waitForExit();
        return 0;
    }
}
